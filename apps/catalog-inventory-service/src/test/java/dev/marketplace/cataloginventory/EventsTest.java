package dev.marketplace.cataloginventory;

import com.fasterxml.jackson.databind.JsonNode;
import dev.marketplace.cataloginventory.messaging.OutboxMessage;
import dev.marketplace.cataloginventory.persistence.PgOutbox;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.sqlclient.Pool;
import jakarta.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import static dev.marketplace.cataloginventory.Api.as;
import static dev.marketplace.cataloginventory.Api.scalar;
import static dev.marketplace.cataloginventory.Api.sku;
import static dev.marketplace.cataloginventory.Kafka.JSON;
import static dev.marketplace.cataloginventory.Kafka.WAIT;
import static dev.marketplace.cataloginventory.Kafka.consume;
import static dev.marketplace.cataloginventory.Kafka.envelope;
import static dev.marketplace.cataloginventory.Kafka.header;
import static dev.marketplace.cataloginventory.Kafka.ofTenant;
import static dev.marketplace.cataloginventory.Kafka.ofType;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class EventsTest {
        @Inject
        Pool pool;
        @Inject
        PgOutbox outbox;

        @Test
        void productAndOfferEventsCarryEnvelopeKeyHeadersAndRequestCorrelation() {
                Api api = new Api().withCategory();
                String id = as(api.seller).header("X-Correlation-Id", "pedido-123")
                                .body(api.productBody("Acme", sku("CAM-P", "P", "50")))
                                .post("/products").then().statusCode(201).header("X-Correlation-Id", "pedido-123")
                                .extract().path("id");
                String skuId = api.skuId(id, "CAM-P");
                as(api.seller).body(Api.price("50")).put("/offers/" + skuId).then().statusCode(201);
                as(api.seller).header("If-Match", "\"0\"")
                                .body("{\"listPrice\":{\"amount\":120.00,\"currency\":\"BRL\"},\"salePrice\":{\"amount\":45.5,\"currency\":\"BRL\"}}")
                                .put("/offers/" + skuId).then().statusCode(200);

                ConsumerRecord<String, String> createdRecord = consume(Kafka.PRODUCT_TOPIC, ofTenant(api.tenant), 1)
                                .get(0);
                JsonNode created = envelope(createdRecord);
                assertEquals(id, createdRecord.key());
                assertEquals("ProductCreated", header(createdRecord, "event-type"));
                assertEquals(created.path("eventId").asText(), header(createdRecord, "event-id"));
                assertEquals(id, created.path("aggregateId").asText());
                assertEquals("pedido-123", created.path("correlationId").asText());
                assertEquals("BRL", created.path("data").path("currency").asText());
                assertEquals("CAM-P", created.path("data").path("skus").path(0).path("sellerSku").asText());
                assertMatchesContract(created);

                List<ConsumerRecord<String, String>> prices = consume(Kafka.OFFER_TOPIC, ofTenant(api.tenant), 2);
                assertEquals(List.of(skuId, skuId), prices.stream().map(ConsumerRecord::key).toList(),
                                "a chave é o SKU da oferta");
                JsonNode first = envelope(prices.get(0));
                assertEquals("50.00", first.path("data").path("salePrice").asText());
                assertEquals(1, first.path("data").path("priceVersion").asInt());
                JsonNode repriced = envelope(prices.get(1));
                assertEquals("45.50", repriced.path("data").path("salePrice").asText());
                assertEquals(2, repriced.path("data").path("priceVersion").asInt());
                assertEquals(id, repriced.path("data").path("productId").asText());
                prices.forEach(r -> assertMatchesContract(envelope(r)));
        }

        @Test
        void reservationLifecyclePublishesReservedDepletedAndReleasedStock() {
                var s = new ReservationResourceTest.Scenario();
                String reservation = s.reserve(s.body(s.item(s.p, 1, "50"))).statusCode(201).extract().path("id");
                as(s.api.checkout).post("/inventory/reservations/" + reservation + "/release").then().statusCode(200);

                List<ConsumerRecord<String, String>> records = consume(Kafka.INVENTORY_TOPIC,
                                ofTenant(s.api.tenant).and(r -> !envelope(r).path("data").path("cause").asText()
                                                .equals("ADJUSTED")),
                                3);
                // WHY: chaves diferentes podem cair em partições diferentes; a ordem só vale
                // dentro de um mesmo agregado.
                ConsumerRecord<String, String> reservedRecord = ofType(records, "InventoryReserved");
                JsonNode reserved = envelope(reservedRecord);
                assertEquals(reservation, reservedRecord.key());
                assertEquals("pedido-1", reserved.path("data").path("orderRef").asText());
                assertEquals(0, reserved.path("data").path("items").path(0).path("stock").path("available").asInt());
                ConsumerRecord<String, String> depletedRecord = ofType(records, "InventoryDepleted");
                JsonNode depleted = envelope(depletedRecord);
                assertEquals(s.p, depletedRecord.key());
                JsonNode released = envelope(ofType(records, "InventoryUpdated"));
                assertEquals("RELEASED", released.path("data").path("cause").asText());
                assertEquals(reservation, released.path("data").path("reservationId").asText());
                assertEquals(1, released.path("data").path("stock").path("available").asInt());
                assertTrue(released.path("data").path("stock").path("version").asLong() > depleted.path("data")
                                .path("stock")
                                .path("version").asLong(), "a versão do saldo só cresce");
                records.forEach(r -> assertMatchesContract(envelope(r)));
        }

        @Test
        void transitionsPublishStatusChangesWithVisibilityAndBlockingPublishesProductBlocked() {
                Api api = new Api().withCategory().approvedStore();
                String id = api.activeProduct(sku("CAM-P", "P", "50"));
                as(api.moderator).body("{\"reason\":\"Produto proibido\"}")
                                .post("/moderation/products/" + id + "/block").then()
                                .statusCode(200);
                ConsumerRecord<String, String> blocked = consume(Kafka.PRODUCT_TOPIC,
                                ofTenant(api.tenant).and(r -> "ProductBlocked".equals(header(r, "event-type"))), 1)
                                .get(0);
                assertEquals("Produto proibido", envelope(blocked).path("data").path("reason").asText());
                assertMatchesContract(envelope(blocked));

                List<JsonNode> changes = consume(Kafka.PRODUCT_TOPIC,
                                ofTenant(api.tenant).and(r -> "ProductStatusChanged".equals(header(r, "event-type"))),
                                3).stream()
                                .map(Kafka::envelope).toList();
                assertEquals(List.of("SUBMIT:false", "APPROVE:true", "BLOCK:false"), changes.stream()
                                .map(e -> e.path("data").path("transition").asText() + ":"
                                                + e.path("data").path("visible").asBoolean())
                                .toList());
                assertEquals("ACTIVE", changes.get(2).path("data").path("from").asText());
                assertEquals("Produto proibido", changes.get(2).path("data").path("reason").asText());
                changes.forEach(EventsTest::assertMatchesContract);
        }

        @Test
        void rolledBackChangeWritesNoEvent() {
                Api api = new Api().withCategory();
                api.createProduct(sku("CAM-P", "P", "50"));
                as(api.seller).body(api.productBody("Acme", sku("CAM-P", "P", "50"))).post("/products").then()
                                .statusCode(409);
                assertEquals(1, outboxRows(api.tenant, "ProductCreated"));
        }

        @Test
        void kybVerdictsAreAppliedOnceInDecisionOrderAndInvalidOnesGoToTheDeadLetterTopic() {
                Api api = new Api();
                UUID eventId = UUID.randomUUID();
                Instant decided = Instant.now();
                String approved = Kafka.verdict(eventId, "SellerApproved", api.tenant, api.store, decided);
                Kafka.publishVerdict(api.store, approved);
                api.awaitEligibility("APPROVED");
                Kafka.publishVerdict(api.store, approved);
                Kafka.publishVerdict(api.store,
                                Kafka.verdict(UUID.randomUUID(), "SellerRejected", api.tenant, api.store,
                                                decided.minusSeconds(60)));
                Kafka.publishVerdict(api.store,
                                Kafka.verdict(UUID.randomUUID(), "SellerApproved", api.tenant, api.store,
                                                decided.plusSeconds(1)));
                Kafka.publishVerdict(api.store, "{nao-e-json");
                Kafka.publishVerdict(api.store, approved.replace("\"eventVersion\":1", "\"eventVersion\":2"));
                Kafka.publishVerdict(api.store,
                                approved.replace("\"data\":{\"storeId\":\"" + api.store + "\"}", "\"data\":{}"));
                Kafka.publishVerdict(api.store, approved.replace(eventId.toString(), "nao-e-uuid"));

                // WHY: mesma chave, mesma partição; quando as inválidas chegam à fila de
                // descarte, as anteriores já foram processadas.
                consume(Kafka.KYB_DLQ, r -> api.store.toString().equals(r.key()), 4);
                as(api.admin).get("/stores/" + api.store + "/eligibility").then().statusCode(200).body("status",
                                is("APPROVED"));
                long deliveries = scalar(pool, "SELECT count(*) AS n FROM inbox_message WHERE event_id = $1", "n",
                                Long.class, eventId);
                assertEquals(1, deliveries);

                // Só a primeira aprovação muda a situação; repetida, obsoleta e de mesma
                // situação não publicam nada.
                assertEquals(1, outboxRows(api.tenant, "StoreEligibilityChanged"));
                JsonNode changed = envelope(consume(Kafka.STORE_TOPIC, ofTenant(api.tenant), 1).get(0));
                assertEquals("PENDING", changed.path("data").path("previousStatus").asText());
                assertTrue(changed.path("data").path("eligible").asBoolean());
                assertEquals(api.store.toString(), changed.path("aggregateId").asText());
                assertEquals("teste", changed.path("correlationId").asText(),
                                "a correlação do KYB/KYC segue para o evento do catálogo");
                assertMatchesContract(changed);
        }

        @Test
        void crashBetweenPublishingAndMarkingRepublishesTheSameEvent() {
                UUID tenant = UUID.randomUUID();
                UUID aggregate = UUID.randomUUID();
                UUID eventId = UUID.randomUUID();
                String payload = """
                                {"eventId":"%s","eventType":"ProductBlocked","eventVersion":1,"source":"catalog-inventory","tenantId":"%s",
                                 "aggregateType":"product","aggregateId":"%s","occurredAt":"%s","correlationId":"queda","data":{"productId":"%s",
                                 "storeId":"%s","reason":"teste de queda"}}"""
                                .formatted(eventId, tenant, aggregate, Instant.now(), aggregate, UUID.randomUUID());
                // WHY: o gatilho faz a marcação falhar depois da confirmação do Kafka, como uma
                // queda do pod entre os dois passos.
                sql("CREATE OR REPLACE FUNCTION test_fail_outbox_mark() RETURNS trigger LANGUAGE plpgsql AS "
                                + "$$BEGIN RAISE EXCEPTION 'queda simulada'; END$$");
                sql("CREATE TRIGGER test_fail_outbox_mark BEFORE UPDATE ON outbox_event FOR EACH ROW "
                                + "WHEN (NEW.id = '" + eventId
                                + "' AND NEW.published_at IS NOT NULL) EXECUTE FUNCTION test_fail_outbox_mark()");
                try {
                        pool.withTransaction(conn -> outbox.append(conn,
                                        List.of(new OutboxMessage(eventId, tenant, Kafka.PRODUCT_TOPIC,
                                                        aggregate.toString(), "ProductBlocked", payload, Instant.now(),
                                                        null))))
                                        .await().atMost(WAIT);
                        consume(Kafka.PRODUCT_TOPIC, r -> aggregate.toString().equals(r.key()), 1);
                        assertNull(publishedAt(eventId), "publicado no Kafka, mas a marcação foi desfeita");
                } finally {
                        sql("DROP TRIGGER IF EXISTS test_fail_outbox_mark ON outbox_event");
                        sql("DROP FUNCTION IF EXISTS test_fail_outbox_mark()");
                }
                org.awaitility.Awaitility.await().atMost(WAIT).until(() -> publishedAt(eventId) != null);
                List<ConsumerRecord<String, String>> copies = consume(Kafka.PRODUCT_TOPIC,
                                r -> aggregate.toString().equals(r.key()), 2);
                copies.forEach(r -> assertEquals(eventId.toString(), envelope(r).path("eventId").asText(),
                                "a cópia repete o eventId"));
        }

        void sql(String statement) {
                pool.query(statement).execute().await().atMost(WAIT);
        }

        java.time.OffsetDateTime publishedAt(UUID eventId) {
                return scalar(pool, "SELECT published_at FROM outbox_event WHERE id = $1", "published_at",
                                java.time.OffsetDateTime.class, eventId);
        }

        long outboxRows(UUID tenant, String type) {
                return scalar(pool, "SELECT count(*) AS n FROM outbox_event WHERE tenant_id = $1 AND event_type = $2",
                                "n", Long.class, tenant, type);
        }

        /**
         * Confere o evento contra os campos obrigatórios do JSON Schema versionado em
         * contracts/.
         */
        static void assertMatchesContract(JsonNode event) {
                JsonNode defs = schema().path("$defs");
                String type = event.path("eventType").asText();
                assertTrue(defs.has(type), "tipo fora do contrato: " + type);
                assertEquals(defs.path(type).path("properties").path("aggregateType").path("const").asText(),
                                event.path("aggregateType").asText());
                assertHas(event, defs.path("envelope").path("required"), "envelope");
                assertHas(event.path("data"), defs.path(type).path("properties").path("data").path("required"), type);
        }

        private static void assertHas(JsonNode node, JsonNode required, String where) {
                Set<String> missing = new HashSet<>();
                required.forEach(field -> {
                        if (!node.has(field.asText()))
                                missing.add(field.asText());
                });
                assertTrue(missing.isEmpty(), where + " sem os campos obrigatórios " + missing);
        }

        private static JsonNode schema() {
                try {
                        return JSON.readTree(new File(System.getProperty("events.contract.file")));
                } catch (IOException e) {
                        throw new AssertionError("Contrato de eventos ilegível", e);
                }
        }
}
