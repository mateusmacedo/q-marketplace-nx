package dev.marketplace.cataloginventory.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Remove os gauges por conexão do cliente SQL reativo, que publicariam valores
 * errados.
 * WHY: o vertx-pg-client cria métricas de cliente a cada conexão física, com o
 * mesmo nome e tags; o Micrometer
 * mantém só o gauge da primeira conexão e descarta os demais. Timers e counters
 * agregam corretamente e permanecem
 * ({@code queue.delay} continua indicando saturação do pool).
 */
public class SqlClientMetricsFilter {
  /**
   * Identifica os gauges por conexão do cliente SQL: tipo gauge,
   * {@code clientType=sql} e nome
   * terminado em {@code .queue.size} ou {@code .current}.
   */
  static boolean isPerConnectionSqlGauge(Meter.Id id) {
    return id.getType() == Meter.Type.GAUGE
        && "sql".equals(id.getTag("clientType"))
        && (id.getName().endsWith(".queue.size") || id.getName().endsWith(".current"));
  }

  @Produces
  @ApplicationScoped
  MeterFilter denyPerConnectionSqlGauges() {
    return MeterFilter.deny(SqlClientMetricsFilter::isPerConnectionSqlGauge);
  }
}
