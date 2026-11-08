package dev.marketplace.cataloginventory.persistence;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.observability.Telemetry;
import dev.marketplace.cataloginventory.application.ImportResult;
import dev.marketplace.cataloginventory.application.ProductImport;
import dev.marketplace.cataloginventory.application.ProductImportRepository;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
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
    private static final String FIND = "SELECT " + COLUMNS
            + " FROM product_import WHERE tenant_id = $1 AND store_id = $2 AND id = $3";
    // WHY: SKIP LOCKED faz cada pod reivindicar um lote diferente sem esperar o bloqueio do outro.
    private static final String CLAIM = """
            UPDATE product_import SET status = 'PROCESSING', started_at = $1
            WHERE id = (SELECT id FROM product_import
                        WHERE status IN ('PENDING', 'PROCESSING') AND (status = 'PENDING' OR started_at < $2)
                        ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)
            RETURNING content, """ + COLUMNS;
    private static final String RESULTS = """
            SELECT line_number, seller_sku, outcome, code, message, product_id FROM product_import_line
            WHERE import_id = $1 ORDER BY line_number""";
    private static final String INSERT_LINE = """
            INSERT INTO product_import_line (import_id, line_number, seller_sku, outcome, code, message, product_id)
            VALUES ($1, $2, $3, $4, $5, $6, $7) ON CONFLICT (import_id, line_number) DO NOTHING""";
    private static final String RENEW = "UPDATE product_import SET started_at = $2 WHERE id = $1 AND status = 'PROCESSING'";
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
    public Uni<List<ImportResult>> results(UUID importId) {
        return pg.bounded(pool.preparedQuery(RESULTS).execute(Tuple.of(importId)))
                .map(rows -> StreamSupport.stream(rows.spliterator(), false)
                        .map(r -> new ImportResult(r.getInteger("line_number"), r.getString("seller_sku"),
                                ImportResult.ImportOutcome.valueOf(r.getString("outcome")), r.getString("code"),
                                r.getString("message"), r.getUUID("product_id")))
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
                .addUUID(r.productId())).toList();
        return pg.bounded(pool.withTransaction(conn -> PgSupport.batch(conn, INSERT_LINE, rows)
                .flatMap(ignored -> conn.preparedQuery(RENEW).execute(Tuple.of(importId, PgSupport.utc(at))))
                .replaceWithVoid()));
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
}
