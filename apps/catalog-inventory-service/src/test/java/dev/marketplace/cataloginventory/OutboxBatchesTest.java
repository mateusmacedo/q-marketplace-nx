package dev.marketplace.cataloginventory;

import dev.marketplace.cataloginventory.messaging.MessagingHousekeeping;
import dev.marketplace.cataloginventory.messaging.OutboxMessage;
import dev.marketplace.cataloginventory.messaging.OutboxRelay;
import dev.marketplace.cataloginventory.persistence.PgOutbox;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import static dev.marketplace.cataloginventory.Api.scalar;
import static dev.marketplace.cataloginventory.Kafka.WAIT;
import static dev.marketplace.cataloginventory.Kafka.consume;
import static dev.marketplace.cataloginventory.Kafka.header;
import static org.junit.jupiter.api.Assertions.*;

/**
 * O relay repete lotes cheios numa mesma execução até o teto, e a limpeza, até
 * esvaziar dentro do prazo; com o relay agendado desligado, só a chamada do
 * teste publica. Tentativas e espera baixas deixam a falha e o estacionamento
 * rápidos.
 */
@QuarkusTest
@TestProfile(OutboxBatchesTest.SmallBatches.class)
class OutboxBatchesTest {
    public static class SmallBatches implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("catalog.events.relay-interval", "off",
                    "catalog.events.relay-batch", "3",
                    "catalog.events.relay-max-batches", "4",
                    "catalog.events.max-attempts", "3",
                    "catalog.events.max-backoff", "1s",
                    "catalog.events.purge-batch", "5");
        }
    }

    @Inject
    Pool pool;
    @Inject
    PgOutbox outbox;
    @Inject
    OutboxRelay relay;
    @Inject
    MessagingHousekeeping housekeeping;
    @Inject
    MeterRegistry registry;

    @Test
    void oneRunPublishesFullBatchesInSeqOrderUntilTheQueueDrains() {
        drainPending();
        UUID tenant = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        append(tenant, key, 8);
        List<UUID> bySeq = idsInSeqOrder(tenant, false);

        relay.relay().await().atMost(WAIT);

        assertEquals(List.of(), idsInSeqOrder(tenant, true), "três lotes (3 + 3 + 2) numa execução");
        List<ConsumerRecord<String, String>> records = consume(Kafka.PRODUCT_TOPIC, r -> key.equals(r.key()), 8);
        assertEquals(bySeq, records.stream().map(r -> UUID.fromString(header(r, "event-id"))).toList(),
                "mesma ordem de seq no tópico");
    }

    @Test
    void oneRunStopsAtTheBatchCapAndTheNextRunPublishesTheRest() {
        drainPending();
        UUID tenant = UUID.randomUUID();
        append(tenant, UUID.randomUUID().toString(), 14);
        List<UUID> bySeq = idsInSeqOrder(tenant, false);

        relay.relay().await().atMost(WAIT);
        assertEquals(bySeq.subList(12, 14), idsInSeqOrder(tenant, true), "teto de 4 lotes de 3");

        relay.relay().await().atMost(WAIT);
        assertEquals(List.of(), idsInSeqOrder(tenant, true));
    }

    @Test
    void failedEventHoldsLaterEventsOfItsKeyButNotOtherKeys() {
        drainPending();
        UUID tenant = UUID.randomUUID();
        String blocked = UUID.randomUUID().toString(), free = UUID.randomUUID().toString();
        // WHY: acima do max.request.size padrão de 1 MB, o produtor Kafka recusa só este registro.
        UUID tooLarge = appendOne(tenant, blocked, "x".repeat(1_200_000));
        UUID heldBack = appendOne(tenant, blocked, "");
        UUID other = appendOne(tenant, free, "");
        try {
            relay.relay().await().atMost(WAIT);

            assertEquals(List.of(tooLarge, heldBack), idsInSeqOrder(tenant, true), "a falha retém o seguinte da chave");
            assertEquals(1, attempts(tooLarge), "o evento recusado conta a tentativa");
            assertEquals(0, attempts(heldBack), "o retido nem foi enviado");
            assertEquals(other, UUID.fromString(header(consume(Kafka.PRODUCT_TOPIC, r -> free.equals(r.key()), 1).get(0),
                    "event-id")), "outra chave segue publicada");
        } finally {
            sql("DELETE FROM outbox_event WHERE tenant_id = $1", tenant);
        }
    }

    @Test
    void failedEventWaitsItsOwnRetryAndIsParkedAfterMaxAttempts() {
        drainPending();
        UUID tenant = UUID.randomUUID();
        UUID tooLarge = appendOne(tenant, UUID.randomUUID().toString(), "x".repeat(1_200_000));
        try {
            relay.relay().await().atMost(WAIT);
            assertEquals(1, attempts(tooLarge));
            assertTrue(nextAttemptAt(tooLarge).isAfter(Instant.now()), "a falha agenda a nova tentativa no futuro");
            relay.relay().await().atMost(WAIT);
            assertEquals(1, attempts(tooLarge), "não volta antes do próprio prazo");

            relayAfterRetryIsDue(tooLarge);
            assertEquals(2, attempts(tooLarge));
            assertFalse(parked(tooLarge));
            relayAfterRetryIsDue(tooLarge);
            assertEquals(3, attempts(tooLarge));
            assertTrue(parked(tooLarge), "estacionado ao chegar a max-attempts");

            relayAfterRetryIsDue(tooLarge);
            assertEquals(3, attempts(tooLarge), "estacionado não volta à fila");
            PgOutbox.Backlog backlog = outbox.backlog().await().atMost(WAIT);
            assertEquals(0, backlog.pending(), "pendentes não contam o estacionado");
            assertEquals(1, backlog.parked());
            assertEquals(Optional.empty(), backlog.oldest(), "a idade não considera o estacionado");
            assertEquals(1.0, registry.get("catalog.outbox.parked").gauge().value());
        } finally {
            sql("DELETE FROM outbox_event WHERE tenant_id = $1", tenant);
        }
    }

    @Test
    void parkedKeyHoldsItsLaterEventsWhileOtherKeysKeepPublishing() {
        drainPending();
        UUID tenant = UUID.randomUUID();
        String parkedKey = UUID.randomUUID().toString(), free = UUID.randomUUID().toString();
        UUID parked = appendOne(tenant, parkedKey, "");
        sql("UPDATE outbox_event SET parked_at = now() WHERE id = $1", parked);
        append(tenant, parkedKey, 4);
        List<UUID> parkedKeyBySeq = idsInSeqOrder(tenant, false);
        append(tenant, free, 2);
        try {
            relay.relay().await().atMost(WAIT);

            assertEquals(parkedKeyBySeq, idsInSeqOrder(tenant, true),
                    "a chave estacionada segura os seus, mais que um lote de 3, sem travar a outra");
            assertEquals(2, consume(Kafka.PRODUCT_TOPIC, r -> free.equals(r.key()), 2).size());
            assertEquals(0, attempts(parkedKeyBySeq.get(1)), "o retido nem foi enviado");

            sql("UPDATE outbox_event SET parked_at = NULL, next_attempt_at = NULL WHERE id = $1", parked);
            relay.relay().await().atMost(WAIT);

            assertEquals(List.of(), idsInSeqOrder(tenant, true), "o reenvio publica a chave inteira");
            assertEquals(parkedKeyBySeq, consume(Kafka.PRODUCT_TOPIC, r -> parkedKey.equals(r.key()), 5).stream()
                    .map(r -> UUID.fromString(header(r, "event-id"))).toList(), "na ordem de seq");
        } finally {
            sql("DELETE FROM outbox_event WHERE tenant_id = $1", tenant);
        }
    }

    @Test
    void purgeRemovesMoreThanOneBatchInOneRun() {
        UUID tenant = UUID.randomUUID();
        String consumer = "teste-" + UUID.randomUUID();
        sql("""
                INSERT INTO outbox_event (id, tenant_id, topic, message_key, event_type, payload, created_at, published_at)
                SELECT gen_random_uuid(), $1::uuid, 'teste', 'chave', 'Teste', '{}', now() - interval '9 days',
                       now() - interval '8 days'
                FROM generate_series(1, 17)""", tenant);
        sql("""
                INSERT INTO inbox_message (consumer, event_id, processed_at)
                SELECT $1::text, gen_random_uuid(), now() - interval '31 days' FROM generate_series(1, 17)""", consumer);

        housekeeping.purge().await().atMost(WAIT);
        assertEquals(0, outboxRows(tenant), "4 lotes de 5 na outbox numa execução");
        assertEquals(0, inboxRows(consumer), "4 lotes de 5 na inbox numa execução");
    }

    /**
     * WHY: eventos deixados por outros testes ocupariam os primeiros lotes; o sentinela só sai quando o relay
     * alcança o fim da fila.
     */
    private void drainPending() {
        appendOne(UUID.randomUUID(), UUID.randomUUID().toString(), "");
        Awaitility.await().atMost(WAIT).until(() -> {
            relay.relay().await().atMost(WAIT);
            return scalar(pool, "SELECT count(*) AS n FROM outbox_event WHERE published_at IS NULL", "n", Long.class)
                    == 0;
        });
    }

    private void append(UUID tenant, String key, int count) {
        List<OutboxMessage> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            messages.add(new OutboxMessage(id, tenant, Kafka.PRODUCT_TOPIC, key, "Teste",
                    "{\"eventId\":\"%s\",\"tenantId\":\"%s\"}".formatted(id, tenant), Instant.now(), null));
        }
        pool.withTransaction(conn -> outbox.append(conn, messages)).await().atMost(WAIT);
    }

    private UUID appendOne(UUID tenant, String key, String padding) {
        UUID id = UUID.randomUUID();
        OutboxMessage message = new OutboxMessage(id, tenant, Kafka.PRODUCT_TOPIC, key, "Teste",
                "{\"eventId\":\"%s\",\"tenantId\":\"%s\",\"padding\":\"%s\"}".formatted(id, tenant, padding),
                Instant.now(), null);
        pool.withTransaction(conn -> outbox.append(conn, List.of(message))).await().atMost(WAIT);
        return id;
    }

    private int attempts(UUID id) {
        return scalar(pool, "SELECT attempts FROM outbox_event WHERE id = $1", "attempts", Integer.class, id);
    }

    private Instant nextAttemptAt(UUID id) {
        return scalar(pool, "SELECT next_attempt_at FROM outbox_event WHERE id = $1", "next_attempt_at",
                OffsetDateTime.class, id).toInstant();
    }

    private boolean parked(UUID id) {
        return scalar(pool, "SELECT parked_at IS NOT NULL AS parked FROM outbox_event WHERE id = $1", "parked",
                Boolean.class, id);
    }

    /** Espera vencer a nova tentativa do evento e roda o relay. */
    private void relayAfterRetryIsDue(UUID id) {
        Instant due = nextAttemptAt(id);
        Awaitility.await().atMost(WAIT).until(() -> Instant.now().isAfter(due));
        relay.relay().await().atMost(WAIT);
    }

    private List<UUID> idsInSeqOrder(UUID tenant, boolean onlyPending) {
        String sql = "SELECT id FROM outbox_event WHERE tenant_id = $1"
                + (onlyPending ? " AND published_at IS NULL" : "") + " ORDER BY seq";
        List<UUID> ids = new ArrayList<>();
        pool.preparedQuery(sql).execute(Tuple.of(tenant)).await().atMost(WAIT)
                .forEach(r -> ids.add(r.getUUID("id")));
        return ids;
    }

    private void sql(String statement, Object param) {
        pool.preparedQuery(statement).execute(Tuple.of(param)).await().atMost(WAIT);
    }

    private long outboxRows(UUID tenant) {
        return scalar(pool, "SELECT count(*) AS n FROM outbox_event WHERE tenant_id = $1", "n", Long.class, tenant);
    }

    private long inboxRows(String consumer) {
        return scalar(pool, "SELECT count(*) AS n FROM inbox_message WHERE consumer = $1", "n", Long.class,
                consumer);
    }
}
