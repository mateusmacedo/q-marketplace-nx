package dev.marketplace.cataloginventory.persistence;

import dev.marketplace.cataloginventory.config.CatalogConfig;
import dev.marketplace.cataloginventory.application.ConcurrentUpdateException;
import dev.marketplace.cataloginventory.application.DuplicateException;
import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import io.vertx.pgclient.PgException;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Conversões e políticas comuns aos repositórios PostgreSQL. */
@ApplicationScoped
public class PgSupport {
  private static final String UNIQUE_VIOLATION = "23505";
  private static final String QUERY_CANCELED = "57014";
  // WHY: set_config com is_local vale só até o fim da transação, e a conexão volta ao pool como estava.
  private static final String CUSTOM_PLAN_AND_DEADLINE = "SELECT set_config('plan_cache_mode', 'force_custom_plan', true), set_config('statement_timeout', $1, true)";
  private final Duration timeout;
  private final String statementTimeout;

  PgSupport(CatalogConfig config) {
    this.timeout = config.persistence().timeout();
    this.statementTimeout = Math.max(1, timeout.toMillis()) + "ms";
  }

  /**
   * Falha com {@link io.smallrye.mutiny.TimeoutException} se o banco não
   * responder no prazo configurado.
   */
  <T> Uni<T> bounded(Uni<T> operation) {
    return operation.ifNoItem().after(timeout).fail();
  }

  /**
   * Como {@link #bounded}, numa transação com plano customizado e o mesmo
   * prazo no servidor, que cancela a consulta atrasada e devolve a conexão;
   * o cancelamento falha como o prazo do cliente.
   */
  Uni<RowSet<Row>> customPlan(Pool pool, String sql, Tuple params) {
    return bounded(pool.withTransaction(conn -> conn.preparedQuery(CUSTOM_PLAN_AND_DEADLINE)
        .execute(Tuple.of(statementTimeout))
        .flatMap(ignored -> conn.preparedQuery(sql).execute(params))))
        .onFailure(PgSupport::canceled).transform(failure -> new TimeoutException());
  }

  private static boolean canceled(Throwable failure) {
    return failure instanceof PgException pg && QUERY_CANCELED.equals(pg.getSqlState());
  }

  /**
   * Traduz violação de unicidade da constraint informada em recusa de negócio;
   * demais erros seguem técnicos.
   */
  static Throwable unique(Throwable failure, String constraint, String code, String message) {
    if (failure instanceof PgException pg && UNIQUE_VIOLATION.equals(pg.getSqlState())
        && constraint.equals(pg.getConstraint()))
      return new DuplicateException(code, message);
    return failure;
  }

  /** Executa o comando em lote na conexão; sem linhas, não consulta o banco. */
  static Uni<Void> batch(SqlConnection conn, String sql, List<Tuple> rows) {
    return rows.isEmpty()
        ? Uni.createFrom().voidItem()
        : conn.preparedQuery(sql).executeBatch(rows).replaceWithVoid();
  }

  /**
   * Falha com {@link ConcurrentUpdateException} se o UPDATE condicional pela
   * versão não alterou exatamente uma linha.
   */
  static void requireOneRow(RowSet<?> result) {
    if (result.rowCount() != 1)
      throw new ConcurrentUpdateException();
  }

  static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  static JsonObject json(Map<String, String> values) {
    return new JsonObject(new HashMap<>(values));
  }

  /** Converte o JSON em mapa ordenado por chave, com os valores em texto. */
  static Map<String, String> strings(JsonObject json) {
    Map<String, String> values = new TreeMap<>();
    json.forEach(entry -> values.put(entry.getKey(), String.valueOf(entry.getValue())));
    return values;
  }
}
