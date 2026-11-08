package dev.marketplace.cataloginventory;

import dev.marketplace.cataloginventory.observability.Lifecycle;
import io.opentelemetry.api.trace.Span;
import dev.marketplace.cataloginventory.application.ReservationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ApplicationNotRunning;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * Expira reservas vencidas e devolve o estoque. O prazo vive no banco, então
 * reiniciar o pod não perde expirações;
 * vários pods podem varrer ao mesmo tempo, porque o adaptador expira cada
 * reserva por um UPDATE condicional na própria transação.
 */
@ApplicationScoped
public class ReservationSweeper {
    private static final Logger LOG = Logger.getLogger(ReservationSweeper.class);
    private final ReservationService reservations;
    private final CatalogConfig config;
    private final Lifecycle lifecycle;
    private final Counter expired;

    ReservationSweeper(ReservationService reservations, CatalogConfig config, MeterRegistry registry,
            Lifecycle lifecycle) {
        this.reservations = reservations;
        this.lifecycle = lifecycle;
        this.config = config;
        this.expired = Counter.builder("catalog.reservations.expired").description("Reservas expiradas pelo varredor")
                .register(registry);
    }

    /**
     * Expira em lote ({@code sweepBatch}) as reservas vencidas e devolve o estoque,
     * contando-as em {@code catalog.reservations.expired} e no atributo
     * {@code marketplace.reservations.expired} do span da execução; cada reserva é
     * uma etapa filha ({@code reservation expire}). Falha é registrada e a execução
     * seguinte tenta de novo; {@code concurrentExecution = SKIP} impede sobreposição
     * no mesmo pod.
     */
    @Scheduled(identity = "reservation-sweeper", every = "${catalog.reservations.sweep-interval}", concurrentExecution = SKIP,
            skipExecutionIf = ApplicationNotRunning.class)
    public Uni<Void> sweep() {
        Span job = Span.current();
        return lifecycle.job(() -> reservations.expireDue(config.reservations().sweepBatch())
                .invoke(count -> job.setAttribute("marketplace.reservations.expired", count))
                .invoke(count -> {
                    if (count > 0) {
                        expired.increment(count);
                        LOG.infof("%d reserva(s) expirada(s) e estoque devolvido", count);
                    }
                })
                .onFailure().invoke(e -> LOG.warn("Falha ao expirar reservas; nova tentativa no próximo ciclo", e))
                .onFailure().recoverWithNull()
                .replaceWithVoid());
    }
}
