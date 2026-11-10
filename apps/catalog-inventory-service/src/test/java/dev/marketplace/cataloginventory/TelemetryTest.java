package dev.marketplace.cataloginventory;

import dev.marketplace.cataloginventory.imports.ProductImportWorker;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.common.vertx.VertxContext;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.awaitility.Awaitility;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static dev.marketplace.cataloginventory.Api.as;
import static dev.marketplace.cataloginventory.Api.scalar;
import static dev.marketplace.cataloginventory.Api.sku;
import static dev.marketplace.cataloginventory.Kafka.WAIT;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Instrumentação de ponta a ponta (ADR 021): a árvore de spans de cada
 * operação, da entrada às consultas e à
 * publicação do evento; contexto de negócio no span e no log de cada
 * requisição; recusas e eventos de domínio no
 * span; e etapas do worker com as mesmas chaves de negócio das requisições do
 * agregado.
 */
@QuarkusTest
@TestProfile(TelemetryTest.Traced.class)
class TelemetryTest {
    public static class Traced implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // WHY: não há Collector no teste; o teste lê os spans em memória, sem
            // exportá-los.
            return Map.of("quarkus.otel.sdk.disabled", "false", "quarkus.otel.traces.exporter", "none",
                    "quarkus.otel.logs.exporter", "none");
        }
    }

    record Logged(String message, Map<String, String> mdc) {
    }

    // WHY: no pacote, o handler recebe tanto as linhas de requisição quanto os logs
    // das tarefas agendadas.
    static final Logger SERVICE = Logger.getLogger("dev.marketplace.cataloginventory");
    final List<Logged> logged = new CopyOnWriteArrayList<>();
    final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            if (logRecord instanceof ExtLogRecord ext)
                logged.add(new Logged(ext.getMessage(), ext.getMdcCopy()));
        }

        @Override
        public void flush() {
            // The capture handler does not buffer log entries.
        }

        @Override
        public void close() {
            // The capture handler does not own external resources.
        }
    };

    @Inject
    RecordedSpans spans;
    @Inject
    Vertx vertx;
    @Inject
    Pool pool;
    @Inject
    ReservationSweeper sweeper;
    @Inject
    ProductImportWorker worker;

    @BeforeEach
    void captureLogs() {
        SERVICE.addHandler(capture);
    }

    @AfterEach
    void releaseLogs() {
        SERVICE.removeHandler(capture);
    }

    @Test
    void requestSpanAndLogCarryTheCallerTheAggregateAndTheDomainEvent() {
        Api api = new Api().withCategory();
        String traceId = newTraceId();
        String id = as(api.seller).header("traceparent", traceParent(traceId))
                .body(api.productBody("Acme", sku("CAM-P", "P", "50"))).post("/products").then().statusCode(201)
                .extract().path("id");

        SpanData created = spans.await(server(traceId, "POST /products"));
        assertEquals(api.tenant.toString(), attribute(created, "marketplace.tenant.id"));
        assertEquals(api.store.toString(), attribute(created, "marketplace.store.id"));
        assertNotNull(attribute(created, "user.id"));
        assertTrue(attribute(created, "user.roles").contains("seller"), attribute(created, "user.roles"));
        assertNotNull(attribute(created, "marketplace.correlation.id"));
        assertEquals(id, attribute(created, "marketplace.product.id"));
        EventData event = created.getEvents().stream().filter(e -> e.getName().equals("domain-event")).findFirst()
                .orElseThrow();
        assertEquals("ProductCreated", event.getAttributes().get(AttributeKey.stringKey("event.type")));
        assertEquals(id, event.getAttributes().get(AttributeKey.stringKey("aggregate.id")));

        Logged line = awaitLog("POST /products 201");
        assertEquals(api.tenant.toString(), line.mdc().get("marketplace.tenant.id"), line.toString());
        assertEquals(api.store.toString(), line.mdc().get("marketplace.store.id"), line.toString());

        String readTrace = newTraceId();
        as(api.seller).header("traceparent", traceParent(readTrace)).get("/products/" + id).then().statusCode(200);
        assertEquals(id, attribute(spans.await(server(readTrace, "GET /products/{id}")), "marketplace.product.id"));
    }

    @Test
    void everyStepOfTheProductLifecycleCarriesTheProductKey() {
        Api api = new Api().withCategory().approvedStore();
        String id = api.activeProduct(sku("CAM-P", "P", "50"));
        for (String route : List.of("POST /products", "POST /products/{id}/submit",
                "POST /moderation/products/{id}/approve"))
            spans.await(span -> span.getKind() == SpanKind.SERVER && span.getName().equals(route)
                    && id.equals(attribute(span, "marketplace.product.id")));
    }

    @Test
    void refusalGoesToTheSpanAndToTheRequestLog() {
        ReservationResourceTest.Scenario s = new ReservationResourceTest.Scenario();
        String traceId = newTraceId();
        String type = as(s.api.checkout).header("traceparent", traceParent(traceId))
                .header("Idempotency-Key", UUID.randomUUID().toString()).body(s.body(s.item(s.p, 50, "50")))
                .post("/inventory/reservations").then().statusCode(409).extract().path("type");

        SpanData refused = spans.await(server(traceId, "POST /inventory/reservations"));
        String problem = attribute(refused, "marketplace.problem.type");
        assertTrue(type.endsWith("/" + problem), type + " x " + problem);
        assertTrue(refused.getEvents().stream().anyMatch(e -> e.getName().equals("problem")));
        SpanData handler = spans.await(child(refused, "ReservationResource.create"));
        assertEquals("refused", attribute(handler, "marketplace.outcome"));
        assertNotEquals(StatusCode.ERROR, handler.getStatus().getStatusCode(), "recusa prevista não marca erro");

        Logged line = awaitLog("POST /inventory/reservations 409");
        assertEquals(problem, line.mdc().get("marketplace.problem.type"), line.toString());
        assertEquals(s.api.tenant.toString(), line.mdc().get("marketplace.tenant.id"), line.toString());
    }

    @Test
    void reservationTraceGoesFromTheEntryToEveryQueryAndToThePublication() {
        ReservationResourceTest.Scenario s = new ReservationResourceTest.Scenario();
        String traceId = newTraceId();
        as(s.api.checkout).header("traceparent", traceParent(traceId))
                .header("Idempotency-Key", UUID.randomUUID().toString()).body(s.body(s.item(s.m, 1, "60")))
                .post("/inventory/reservations").then().statusCode(201);

        SpanData server = spans.await(server(traceId, "POST /inventory/reservations"));
        assertTrue(server.getEvents().stream()
                .anyMatch(e -> e.getName().equals("quarkus.security.authentication.success")));
        assertTrue(server.getEvents().stream()
                .anyMatch(e -> e.getName().equals("quarkus.security.authorization.success")));
        SpanData handler = spans.await(child(server, "ReservationResource.create"));
        spans.await(child(handler, "PgReservationRepository.findByIdempotencyKey"));
        spans.await(child(handler, "PgInventoryRepository.candidates"));
        SpanData insert = spans.await(child(handler, "PgReservationRepository.insert"));
        SpanData append = spans.await(child(insert, "PgOutbox.append"));
        assertFalse(queriesOf(insert).isEmpty(), "os INSERT e o UPDATE da reserva ficam no adaptador");
        assertEquals(List.of("INSERT outbox_event"), queriesOf(append));
        assertTrue(queriesOf(server).isEmpty(), "nenhuma consulta fica solta no span da requisição");
        assertEquals(s.api.tenant.toString(), attribute(server, "marketplace.tenant.id"),
                "as chaves seguem na operação");

        SpanData publish = spans.await(span -> span.getTraceId().equals(traceId)
                && span.getName().equals("outbox publish catalog-inventory.inventory.v1"));
        spans.await(span -> span.getKind() == SpanKind.PRODUCER && publish.getSpanId().equals(span.getParentSpanId()));
        assertFalse(publish.getLinks().isEmpty(), "a publicação liga à execução do relay");
        assertNotNull(publish.getAttributes().get(AttributeKey.longKey("marketplace.outbox.wait_ms")));
    }

    @Test
    void queueReadsTheEligibilityOfEveryStoreOfThePageInOneQuery() {
        Api api = new Api().withCategory();
        for (int i = 0; i < 3; i++) {
            String owner = TestTokens.seller(api.tenant, UUID.randomUUID());
            String id = as(owner).body(api.productBody("Acme", sku("CAM-P", "P", "50"))).post("/products").then()
                    .statusCode(201).extract().path("id");
            String skuId = as(owner).get("/products/" + id).then().extract().path("skus[0].id");
            as(owner).body(Api.price("50")).put("/offers/" + skuId).then().statusCode(201);
            as(owner).post("/products/" + id + "/submit").then().statusCode(200);
        }
        String traceId = newTraceId();
        as(api.moderator).header("traceparent", traceParent(traceId)).get("/moderation/products").then()
                .statusCode(200);

        SpanData handler = spans.await(span -> span.getTraceId().equals(traceId)
                && span.getName().equals("ModerationResource.queue"));
        spans.await(child(handler, "PgSellerEligibilityRepository.statusesOf"));
        assertEquals(1, spans.all(child(handler, "PgSellerEligibilityRepository.statusesOf")).size(),
                "uma consulta para as três lojas da página");
        assertTrue(spans.all(child(handler, "PgSellerEligibilityRepository.statusOf")).isEmpty());
    }

    @Test
    void parallelReadsStaySiblingsAndTheConsumerIsAnEntry() {
        Api api = new Api().withCategory().approvedStore();
        String productId = api.activeProduct(sku("CAM-V", "P", "50"));
        String traceId = newTraceId();
        given().header("traceparent", traceParent(traceId)).get("/storefront/" + api.tenant + "/products/" + productId)
                .then().statusCode(200);

        // WHY: a vitrine lê situação da loja e ofertas em paralelo (Uni.combine); cada
        // leitura fica filha do resource.
        SpanData handler = spans.await(span -> span.getTraceId().equals(traceId)
                && span.getName().equals("StorefrontResource.get"));
        assertFalse(queriesOf(spans.await(child(handler, "PgSellerEligibilityRepository.statusOf"))).isEmpty());
        assertFalse(queriesOf(spans.await(child(handler, "PgOfferRepository.ofProduct"))).isEmpty());

        SpanData consume = spans.await(span -> span.getName().equals("SellerVerdictConsumer.consume")
                && !spans.all(parent -> parent.getKind() == SpanKind.CONSUMER
                        && parent.getSpanId().equals(span.getParentSpanId())).isEmpty());
        spans.await(child(consume, "PgSellerEligibilityRepository.apply"));
    }

    @Test
    void storefrontCacheStepsAreChildrenOfTheResourceWithTheRedisCommandsInside() {
        Api api = new Api().withCategory().approvedStore();
        String productId = api.activeProduct(sku("CAM-C", "P", "50"));
        String loaded = newTraceId();
        given().header("traceparent", traceParent(loaded)).get("/storefront/" + api.tenant + "/products/" + productId)
                .then().statusCode(200);

        // WHY: na falta, a leitura do cache, a carga do banco e a gravação no cache
        // ficam irmãs, filhas do resource.
        SpanData missed = spans.await(span -> span.getTraceId().equals(loaded)
                && span.getName().equals("StorefrontResource.get"));
        SpanData read = spans.await(child(missed, "CatalogCache.read"));
        assertEquals("miss", attribute(read, "marketplace.cache.result"));
        assertEquals("storefront", attribute(read, "marketplace.cache.name"));
        assertFalse(queriesOf(read).isEmpty(), "o comando Redis fica no span da leitura do cache");
        spans.await(child(missed, "PgProductRepository.findById"));
        assertFalse(queriesOf(spans.await(child(missed, "CatalogCache.write"))).isEmpty());

        String cached = newTraceId();
        given().header("traceparent", traceParent(cached)).get("/storefront/" + api.tenant + "/products/" + productId)
                .then().statusCode(200);
        SpanData hit = spans.await(span -> span.getTraceId().equals(cached)
                && span.getName().equals("StorefrontResource.get"));
        assertEquals("hit", attribute(spans.await(child(hit, "CatalogCache.read")), "marketplace.cache.result"));
        assertFalse(queriesOf(spans.await(child(hit, "PgInventoryRepository.levels"))).isEmpty(),
                "o estoque segue lido do banco");
        assertTrue(spans.all(child(hit, "PgProductRepository.findById")).isEmpty(), "o acerto não lê o produto");
    }

    @Test
    void expirationIsAStepOfTheSweeperWithTheReservationKey() throws Exception {
        ReservationResourceTest.Scenario s = new ReservationResourceTest.Scenario();
        String traceId = newTraceId();
        String id = as(s.api.checkout).header("traceparent", traceParent(traceId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body("{\"ttlSeconds\":1,\"items\":[" + s.item(s.m, 1, "60") + "]}").post("/inventory/reservations")
                .then().statusCode(201).extract().path("id");
        assertEquals(id, attribute(spans.await(server(traceId, "POST /inventory/reservations")),
                "marketplace.reservation.id"), "a criação leva a chave da reserva");
        Awaitility.await().atMost(WAIT).until(() -> scalar(pool,
                "SELECT count(*) AS n FROM inventory_reservation WHERE id = $1 AND expires_at <= now()",
                "n", Long.class, UUID.fromString(id)) > 0);

        onVertx(sweeper::sweep);

        SpanData expire = spans.await(span -> span.getName().equals("reservation expire")
                && id.equals(attribute(span, "marketplace.reservation.id")));
        assertEquals(s.api.tenant.toString(), attribute(expire, "marketplace.tenant.id"));
        assertFalse(spans.all(span -> span.getKind() == SpanKind.CLIENT
                && expire.getSpanId().equals(span.getParentSpanId())).isEmpty(),
                "as consultas da expiração são filhas");
        assertTrue(scalar(pool, "SELECT count(*) AS n FROM outbox_event WHERE event_type = 'InventoryUpdated'"
                + " AND trace_parent LIKE $1", "n", Long.class, "00-" + expire.getTraceId() + "-%") > 0,
                "o evento da expiração sai no trace da etapa");
        Logged summary = awaitLog(line -> line.message().endsWith("reserva(s) expirada(s) e estoque devolvido"));
        assertNull(summary.mdc().get("marketplace.reservation.id"), "o resumo do lote não leva a chave de uma reserva");
    }

    @Test
    void importProcessingIsAStepWithTheImportKey() {
        Api api = new Api().withCategory();
        String traceId = newTraceId();
        String location = ProductImportResourceTest.csv(api.seller, ProductImportResourceTest.HEADER + "\n"
                + ProductImportResourceTest.line("traced", "TRC-P", "P", "", "59.90", 2))
                .header("traceparent", traceParent(traceId)).post("/products/imports").then().statusCode(202)
                .extract().header("Location");
        String importId = location.substring(location.lastIndexOf('/') + 1);
        assertEquals(importId, attribute(spans.await(server(traceId, "POST /products/imports")),
                "marketplace.import.id"), "o envio leva a chave do lote");

        Awaitility.await().atMost(WAIT).until(() -> {
            onVertx(worker::run);
            return "COMPLETED".equals(as(api.seller).get(location).then().extract().path("status"));
        });

        SpanData processed = spans.await(span -> span.getName().equals("product-import process")
                && importId.equals(attribute(span, "marketplace.import.id")));
        assertEquals(api.store.toString(), attribute(processed, "marketplace.store.id"));
        assertTrue(scalar(pool, "SELECT count(*) AS n FROM outbox_event WHERE event_type = 'ProductCreated'"
                + " AND tenant_id = $1 AND trace_parent LIKE $2", "n", Long.class, api.tenant,
                "00-" + processed.getTraceId() + "-%") > 0,
                "o produto criado pela importação publica no trace da etapa");
    }

    @Test
    void scheduledExecutionStaysActiveForItsQueries() {
        // O relay roda pelo agendador real a cada segundo no perfil de teste. A trava
        // consultiva (SELECT), dentro da
        // transação cujo callback perdia o contexto, e a contagem do backlog (SELECT
        // outbox_event) ficam nos spans do
        // adaptador, filhos da execução.
        SpanData relay = spans.await(span -> span.getName().equals("outbox-relay") && spans
                .all(adapter -> adapter.getName().startsWith("PgOutbox.")
                        && span.getSpanId().equals(adapter.getParentSpanId()))
                .stream().flatMap(adapter -> queriesOf(adapter).stream())
                .collect(java.util.stream.Collectors.toSet()).containsAll(List.of("SELECT", "SELECT outbox_event")));
        assertNotNull(relay.getAttributes().get(AttributeKey.longKey("marketplace.outbox.published")),
                "a execução registra quantos eventos publicou");
    }

    /** Roda o trabalho num contexto Vert.x duplicado, como o agendador faz. */
    <T> T onVertx(Supplier<Uni<T>> work) throws Exception {
        CompletableFuture<T> done = new CompletableFuture<>();
        VertxContext.createNewDuplicatedContext(vertx.getOrCreateContext())
                .runOnContext(ignored -> work.get().subscribe().with(done::complete, done::completeExceptionally));
        return done.get(30, TimeUnit.SECONDS);
    }

    Logged awaitLog(String start) {
        return awaitLog(line -> line.message().startsWith(start));
    }

    Logged awaitLog(java.util.function.Predicate<Logged> match) {
        return Awaitility.await().atMost(WAIT).until(() -> logged.stream().filter(match).findFirst().orElse(null),
                Objects::nonNull);
    }

    static java.util.function.Predicate<SpanData> server(String traceId, String name) {
        return span -> span.getKind() == SpanKind.SERVER && span.getTraceId().equals(traceId)
                && span.getName().equals(name);
    }

    static java.util.function.Predicate<SpanData> child(SpanData parent, String name) {
        return span -> span.getName().equals(name) && parent.getSpanId().equals(span.getParentSpanId());
    }

    /** Nomes das consultas SQL filhas diretas do span. */
    List<String> queriesOf(SpanData parent) {
        return spans.all(span -> span.getKind() == SpanKind.CLIENT && parent.getSpanId().equals(span.getParentSpanId()))
                .stream().map(SpanData::getName).toList();
    }

    static String attribute(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }

    static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    static String traceParent(String traceId) {
        return "00-" + traceId + "-00f067aa0ba902b7-01";
    }
}
