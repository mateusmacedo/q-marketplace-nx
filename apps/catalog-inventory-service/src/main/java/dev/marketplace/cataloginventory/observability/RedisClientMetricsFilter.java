package dev.marketplace.cataloginventory.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Remove os gauges por conexão do cliente Redis do cache (ADR 024), como
 * {@link SqlClientMetricsFilter} faz com o
 * SQL. WHY: o vertx-redis-client cria métricas de cliente a cada conexão nova,
 * com o mesmo nome e tags; o Micrometer
 * mantém só o gauge da primeira e avisa em WARN no registro da segunda. Timers
 * e counters agregam e permanecem.
 */
public class RedisClientMetricsFilter {
    /**
     * Gauge com {@code clientType=redis} e nome terminado em {@code .queue.size} ou
     * {@code .current}.
     */
    static boolean isPerConnectionRedisGauge(Meter.Id id) {
        return id.getType() == Meter.Type.GAUGE
                && "redis".equals(id.getTag("clientType"))
                && (id.getName().endsWith(".queue.size") || id.getName().endsWith(".current"));
    }

    @Produces
    @Singleton
    MeterFilter denyPerConnectionRedisGauges() {
        return MeterFilter.deny(RedisClientMetricsFilter::isPerConnectionRedisGauge);
    }
}
