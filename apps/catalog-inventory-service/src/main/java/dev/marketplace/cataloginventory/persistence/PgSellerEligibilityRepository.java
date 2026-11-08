package dev.marketplace.cataloginventory.persistence;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.application.SellerEligibilityRepository;
import dev.marketplace.cataloginventory.application.SellerVerdict;
import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import dev.marketplace.cataloginventory.outbox.EventOrigin;
import dev.marketplace.cataloginventory.outbox.IntegrationEvents;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@Traced
public class PgSellerEligibilityRepository implements SellerEligibilityRepository {
    private static final String SELECT = "SELECT status FROM seller_eligibility WHERE tenant_id = $1 AND store_id = $2";
    private static final String DELIVERED = "INSERT INTO inbox_message (consumer, event_id, processed_at) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING";
    // WHY: consulta separada do upsert; uma CTE com FOR UPDATE só seria lida depois
    // da própria atualização e viria vazia.
    private static final String LOCK_PREVIOUS = "SELECT status FROM seller_eligibility WHERE tenant_id = $1 AND store_id = $2 FOR UPDATE";
    // WHY: vereditos chegam fora de ordem; só o mais recente (ou empatado)
    // substitui a situação registrada.
    private static final String APPLY = """
            INSERT INTO seller_eligibility (tenant_id, store_id, status, updated_at, decided_at) VALUES ($1, $2, $3, $4, $5)
            ON CONFLICT (tenant_id, store_id) DO UPDATE
                SET status = EXCLUDED.status, updated_at = EXCLUDED.updated_at, decided_at = EXCLUDED.decided_at
                WHERE seller_eligibility.decided_at <= EXCLUDED.decided_at""";
    private final Pool pool;
    private final PgSupport pg;
    private final Clock clock;
    private final PgOutbox outbox;
    private final IntegrationEvents events;

    PgSellerEligibilityRepository(Pool pool, PgSupport pg, Clock clock, PgOutbox outbox, IntegrationEvents events) {
        this.pool = pool;
        this.pg = pg;
        this.clock = clock;
        this.outbox = outbox;
        this.events = events;
    }

    @Override
    public Uni<SellerStatus> statusOf(UUID tenantId, UUID storeId) {
        return pg.bounded(pool.preparedQuery(SELECT).execute(Tuple.of(tenantId, storeId)))
                .map(PgSellerEligibilityRepository::status);
    }

    @Override
    public Uni<SellerVerdict.Outcome> apply(String consumer, SellerVerdict v) {
        Instant now = clock.instant();
        EventOrigin origin = EventOrigin.current();
        return pg.bounded(pool.withTransaction(
                conn -> conn.preparedQuery(DELIVERED).execute(Tuple.of(consumer, v.eventId(), PgSupport.utc(now)))
                        .flatMap(delivered -> {
                            if (delivered.rowCount() == 0)
                                return Uni.createFrom().item(SellerVerdict.Outcome.DUPLICATE);
                            return conn.preparedQuery(LOCK_PREVIOUS).execute(Tuple.of(v.tenantId(), v.storeId()))
                                    .flatMap(found -> {
                                        SellerStatus previous = status(found);
                                        return conn.preparedQuery(APPLY)
                                                .execute(Tuple.of(v.tenantId(), v.storeId(), v.status().name(),
                                                        PgSupport.utc(now), PgSupport.utc(v.decidedAt())))
                                                .flatMap(applied -> {
                                                    if (applied.rowCount() == 0)
                                                        return Uni.createFrom().item(SellerVerdict.Outcome.STALE);
                                                    if (previous == v.status())
                                                        return Uni.createFrom().item(SellerVerdict.Outcome.APPLIED);
                                                    return outbox
                                                            .append(conn,
                                                                    List.of(events.fromEligibility(v.tenantId(),
                                                                            v.storeId(), previous,
                                                                            v.status(), v.decidedAt(), now, origin)))
                                                            .replaceWith(SellerVerdict.Outcome.APPLIED);
                                                });
                                    });
                        })));
    }

    /** Situação lida da consulta; loja sem registro ainda está {@code PENDING}. */
    private static SellerStatus status(RowSet<Row> rows) {
        return rows.size() == 0 ? SellerStatus.PENDING
                : SellerStatus.valueOf(rows.iterator().next().getString("status"));
    }
}
