package dev.marketplace.cataloginventory.messaging;

import dev.marketplace.cataloginventory.observability.Lifecycle;
import io.opentelemetry.api.trace.Span;
import dev.marketplace.cataloginventory.config.CatalogConfig;
import dev.marketplace.cataloginventory.persistence.PgOutbox;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ApplicationNotRunning;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Instant;
import org.jboss.logging.Logger;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * Remove da outbox os eventos já publicados e do inbox as entregas antigas,
 * conforme a retenção configurada.
 */
@ApplicationScoped
public class MessagingHousekeeping {
    private static final Logger LOG = Logger.getLogger(MessagingHousekeeping.class);

    private final PgOutbox outbox;
    private final CatalogConfig.Events config;
    private final Clock clock;
    private final Lifecycle lifecycle;

    MessagingHousekeeping(PgOutbox outbox, CatalogConfig config, Clock clock, Lifecycle lifecycle) {
        this.outbox = outbox;
        this.lifecycle = lifecycle;
        this.config = config.events();
        this.clock = clock;
    }

    /**
     * Remove os eventos publicados e as entregas consumidas fora da retenção, em
     * lotes repetidos enquanto vêm cheios, até {@code purgeMaxDuration} somando as
     * duas tabelas. O total removido vai no atributo {@code marketplace.purge.removed}
     * do span da execução. Falha é registrada e a execução seguinte tenta de novo.
     */
    @Scheduled(identity = "messaging-purge", every = "${catalog.events.purge-interval}", concurrentExecution = SKIP,
            skipExecutionIf = ApplicationNotRunning.class)
    public Uni<Void> purge() {
        Instant now = clock.instant();
        Span job = Span.current();
        return lifecycle.job(() -> outbox.purge(now.minus(config.outboxRetention()), now.minus(config.inboxRetention()),
                config.purgeBatch(), config.purgeMaxDuration())
                .invoke(removed -> {
                    job.setAttribute("marketplace.purge.removed", removed);
                    if (removed > 0)
                        LOG.infof("%d registro(s) antigos de outbox e inbox removidos", removed);
                })
                .onFailure()
                .invoke(e -> LOG.warn("Falha na limpeza de outbox e inbox; nova tentativa no próximo ciclo", e))
                .onFailure().recoverWithNull()
                .replaceWithVoid());
    }
}
