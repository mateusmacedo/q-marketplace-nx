package dev.marketplace.cataloginventory.persistence;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.observability.Telemetry;
import dev.marketplace.cataloginventory.application.ImportResult;
import dev.marketplace.cataloginventory.application.ImportResult.ImportOutcome;
import dev.marketplace.cataloginventory.application.ProductImport;
import dev.marketplace.cataloginventory.application.ProductImportRepository;
import dev.marketplace.cataloginventory.domain.Reason;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

/** Lotes de importação de produtos e o resultado por linha (ADR 016). */
@ApplicationScoped
@Traced
public class PgProductImportRepository implements ProductImportRepository {
  private static final String COLUMNS = "id, tenant_id, store_id, actor, status, total_lines, created_at, started_at, finished_at";
  private static final String INSERT = """
      INSERT INTO product_import (id, tenant_id, store_id, actor, status, content, total_lines, created_at)
      VALUES ($1, $2, $3, $4, $5, $6, $7, $8)""";
  private static final String COUNTS = "created_lines, updated_lines, unchanged_lines, failed_lines";
  private static final String FIND = "SELECT " + COLUMNS
      + " FROM product_import WHERE tenant_id = $1 AND store_id = $2 AND id = $3";
  private static final String FIND_SUMMARY = "SELECT " + COLUMNS + ", " + COUNTS
      + " FROM product_import WHERE tenant_id = $1 AND store_id = $2 AND id = $3";
  // WHY: SKIP LOCKED faz cada pod reivindicar um lote diferente sem esperar o bloqueio do outro.
  private static final String CLAIM = """
      UPDATE product_import SET status = 'PROCESSING', started_at = $1
      WHERE id = (SELECT id FROM product_import
                  WHERE status IN ('PENDING', 'PROCESSING') AND (status = 'PENDING' OR started_at < $2)
                  ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)
      RETURNING content, """ + COLUMNS;
  private static final String RESULTS = """
      SELECT line_number, seller_sku, outcome, code, message, reasons, product_id FROM product_import_line
      WHERE import_id = $1 ORDER BY line_number""";
  private static final String LIST = "SELECT " + COLUMNS + ", " + COUNTS + " FROM product_import"
      + " WHERE tenant_id = $1 AND store_id = $2%s ORDER BY created_at DESC, id DESC LIMIT $%d";
  // WHY: o RETURNING traz só as linhas inseridas; a que já tinha resultado (retomada depois do lease) não soma de
  // novo nas contagens do lote.
  private static final String INSERT_LINE = """
      INSERT INTO product_import_line (import_id, line_number, seller_sku, outcome, code, message, reasons,
          product_id)
      VALUES ($1, $2, $3, $4, $5, $6, $7, $8) ON CONFLICT (import_id, line_number) DO NOTHING RETURNING outcome""";
  private static final String RENEW = """
      UPDATE product_import SET started_at = CASE WHEN status = 'PROCESSING' THEN $2 ELSE started_at END,
          created_lines = created_lines + $3, updated_lines = updated_lines + $4,
          unchanged_lines = unchanged_lines + $5, failed_lines = failed_lines + $6
      WHERE id = $1""";
  private static final String COMPLETE = """
      UPDATE product_import SET status = 'COMPLETED', finished_at = $2 WHERE id = $1 AND status = 'PROCESSING'""";

  private final Pool pool;
  private final PgSupport pg;

  PgProductImportRepository(Pool pool, PgSupport pg) {
    this.pool = pool;
    this.pg = pg;
  }

  @Override
  public Uni<Void> insert(ProductImport job, String content) {
    Tuple row = Tuple.tuple().addUUID(job.id()).addUUID(job.tenantId()).addUUID(job.storeId())
        .addString(job.actor()).addString(job.status().name()).addString(content)
        .addInteger(job.totalLines()).addValue(PgSupport.utc(job.createdAt()));
    // WHY: o id nasce no servidor; a tag liga o envio ao processamento pelo worker, que tem o mesmo id (ADR 021).
    Telemetry.tag("marketplace.import.id", job.id());
    return pg.bounded(pool.preparedQuery(INSERT).execute(row)).replaceWithVoid();
  }

  @Override
  public Uni<Optional<ProductImport>> findImport(UUID tenantId, UUID storeId, UUID id) {
    return pg.bounded(pool.preparedQuery(FIND).execute(Tuple.of(tenantId, storeId, id)))
        .map(rows -> StreamSupport.stream(rows.spliterator(), false).map(PgProductImportRepository::toImport)
            .findFirst());
  }

  @Override
  public Uni<Optional<Summary>> findSummary(UUID tenantId, UUID storeId, UUID id) {
    return pg.bounded(pool.preparedQuery(FIND_SUMMARY).execute(Tuple.of(tenantId, storeId, id)))
        .map(rows -> StreamSupport.stream(rows.spliterator(), false).map(PgProductImportRepository::toSummary)
            .findFirst());
  }

  @Override
  public Uni<List<Summary>> list(UUID tenantId, UUID storeId, Optional<Position> after, int limit) {
    List<Object> params = new ArrayList<>(List.of(tenantId, storeId));
    String position = "";
    if (after.isPresent()) {
      params.add(PgSupport.utc(after.get().createdAt()));
      params.add(after.get().id());
      position = " AND (created_at, id) < ($3, $4)";
    }
    params.add(limit);
    return pg.bounded(pool.preparedQuery(LIST.formatted(position, params.size())).execute(Tuple.from(params)))
        .map(rows -> StreamSupport.stream(rows.spliterator(), false).map(PgProductImportRepository::toSummary)
            .toList());
  }

  @Override
  public Uni<List<ImportResult>> results(UUID importId) {
    return pg.bounded(pool.preparedQuery(RESULTS).execute(Tuple.of(importId)))
        .map(rows -> StreamSupport.stream(rows.spliterator(), false)
            .map(r -> new ImportResult(r.getInteger("line_number"), r.getString("seller_sku"),
                ImportResult.ImportOutcome.valueOf(r.getString("outcome")), r.getString("code"),
                r.getString("message"), r.getUUID("product_id"), reasons(r.getJsonArray("reasons"))))
            .toList());
  }

  @Override
  public Uni<Optional<Claimed>> claimNext(Instant now, Instant staleBefore) {
    return pg.bounded(pool.preparedQuery(CLAIM).execute(Tuple.of(PgSupport.utc(now), PgSupport.utc(staleBefore))))
        .map(rows -> StreamSupport.stream(rows.spliterator(), false)
            .map(r -> new Claimed(toImport(r), r.getString("content"))).findFirst());
  }

  @Override
  public Uni<Void> recordResults(UUID importId, List<ImportResult> results, Instant at) {
    List<Tuple> rows = results.stream().map(r -> Tuple.tuple().addUUID(importId).addInteger(r.lineNumber())
        .addString(r.sellerSku()).addString(r.outcome().name()).addString(r.code()).addString(r.message())
        .addValue(json(r.reasons())).addUUID(r.productId())).toList();
    return pg.bounded(pool.withTransaction(conn -> inserted(conn, rows)
        .flatMap(counts -> conn.preparedQuery(RENEW).execute(Tuple.tuple().addUUID(importId)
            .addValue(PgSupport.utc(at)).addInteger(counts.getOrDefault(ImportOutcome.CREATED, 0))
            .addInteger(counts.getOrDefault(ImportOutcome.UPDATED, 0))
            .addInteger(counts.getOrDefault(ImportOutcome.UNCHANGED, 0))
            .addInteger(counts.getOrDefault(ImportOutcome.FAILED, 0))))
        .replaceWithVoid()));
  }

  /** Grava as linhas e conta por desfecho as que entraram agora. */
  private static Uni<Map<ImportOutcome, Integer>> inserted(SqlConnection conn, List<Tuple> rows) {
    if (rows.isEmpty())
      return Uni.createFrom().item(Map.of());
    return conn.preparedQuery(INSERT_LINE).executeBatch(rows).map(first -> {
      Map<ImportOutcome, Integer> counts = new EnumMap<>(ImportOutcome.class);
      for (RowSet<Row> result = first; result != null; result = result.next())
        result.forEach(row -> counts.merge(ImportOutcome.valueOf(row.getString("outcome")), 1, Integer::sum));
      return counts;
    });
  }

  @Override
  public Uni<Void> complete(UUID importId, Instant at) {
    return pg.bounded(pool.preparedQuery(COMPLETE).execute(Tuple.of(importId, PgSupport.utc(at))))
        .replaceWithVoid();
  }

  private static ProductImport toImport(Row r) {
    return new ProductImport(r.getUUID("id"), r.getUUID("tenant_id"), r.getUUID("store_id"), r.getString("actor"),
        ProductImport.ImportStatus.valueOf(r.getString("status")), r.getInteger("total_lines"),
        PgSupport.instant(r.getOffsetDateTime("created_at")),
        PgSupport.instant(r.getOffsetDateTime("started_at")),
        PgSupport.instant(r.getOffsetDateTime("finished_at")));
  }

  private static Summary toSummary(Row r) {
    return new Summary(toImport(r), r.getInteger("created_lines"), r.getInteger("updated_lines"),
        r.getInteger("unchanged_lines"), r.getInteger("failed_lines"));
  }

  /** Motivos da linha em JSON; sem motivos, a coluna fica nula. */
  static JsonArray json(List<Reason> reasons) {
    if (reasons.isEmpty())
      return null;
    JsonArray array = new JsonArray();
    reasons.forEach(r -> array.add(new JsonObject().put("code", r.code())
        .put("params", new JsonArray(new ArrayList<>(r.params()))).put("message", r.message())));
    return array;
  }

  static List<Reason> reasons(JsonArray array) {
    if (array == null)
      return List.of();
    List<Reason> reasons = new ArrayList<>();
    for (int i = 0; i < array.size(); i++) {
      JsonObject r = array.getJsonObject(i);
      List<String> params = r.getJsonArray("params").stream().map(String::valueOf).toList();
      reasons.add(new Reason(r.getString("code"), params, r.getString("message")));
    }
    return reasons;
  }
}
