package dev.marketplace.cataloginventory.observability;

import io.opentelemetry.api.trace.Span;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ApplicationNotRunning;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicLong;
import org.jboss.logging.Logger;
import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * Publica {@code catalog.skus.active} e {@code catalog.skus.sellable} do
 * marketplace inteiro, sem tag de tenant para não multiplicar séries (ADR 017).
 * Só o papel worker (ADR 020) recalcula os gauges; cada pod worker publica o
 * mesmo total, então agregue com {@code max}, não com {@code sum}.
 */
@ApplicationScoped
public class SkuGauges {
    private static final Logger LOG = Logger.getLogger(SkuGauges.class);
    private final SkuCountsSource metrics;
    private final Lifecycle lifecycle;
    private final AtomicLong active = new AtomicLong();
    private final AtomicLong sellable = new AtomicLong();

    SkuGauges(SkuCountsSource metrics, MeterRegistry registry, Lifecycle lifecycle) {
        this.metrics = metrics;
        this.lifecycle = lifecycle;
        Gauge.builder("catalog.skus.active", active, AtomicLong::get)
                .description("SKUs de produtos ativos")
                .register(registry);
        Gauge.builder("catalog.skus.sellable", sellable, AtomicLong::get)
                .description("SKUs ativos de lojas aprovadas e com saldo disponível")
                .register(registry);
    }

    /**
     * Recalcula os dois gauges com uma consulta agregada. Falha é registrada e os
     * gauges mantêm o último valor; {@code concurrentExecution = SKIP} impede
     * sobreposição no mesmo pod. As duas contagens vão como atributos do span da
     * execução ({@code marketplace.skus.active} e
     * {@code marketplace.skus.sellable}).
     */
    @Scheduled(identity = "sku-gauges", every = "${catalog.metrics.refresh-interval}", concurrentExecution = SKIP, skipExecutionIf = ApplicationNotRunning.class)
    public Uni<Void> refresh() {
        Span job = Span.current();
        return lifecycle.job(() -> metrics.skuCounts()
                .invoke(counts -> {
                    job.setAttribute("marketplace.skus.active", counts.active());
                    job.setAttribute("marketplace.skus.sellable", counts.sellable());
                    active.set(counts.active());
                    sellable.set(counts.sellable());
                })
                .onFailure()
                .invoke(e -> LOG.warn("Falha ao contar SKUs; os gauges mantêm o último valor até o "
                        + "próximo ciclo", e))
                .onFailure().recoverWithNull()
                .replaceWithVoid());
    }
}
