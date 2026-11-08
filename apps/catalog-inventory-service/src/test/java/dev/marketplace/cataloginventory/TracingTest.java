package dev.marketplace.cataloginventory;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.mutiny.sqlclient.Pool;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import static dev.marketplace.cataloginventory.Api.as;
import static dev.marketplace.cataloginventory.Api.scalar;
import static dev.marketplace.cataloginventory.Api.sku;
import static dev.marketplace.cataloginventory.Kafka.consume;
import static dev.marketplace.cataloginventory.Kafka.header;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Com o OpenTelemetry ligado, o evento publicado pelo relay continua o trace da
 * requisição que o gravou.
 */
@QuarkusTest
@TestProfile(TracingTest.Tracing.class)
class TracingTest {
    public static class Tracing implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // WHY: não há Collector no teste; o teste lê os spans em memória, sem exportá-los.
            return Map.of("quarkus.otel.sdk.disabled", "false", "quarkus.otel.traces.exporter", "none",
                    "quarkus.otel.logs.exporter", "none");
        }
    }

    @Inject
    Pool pool;

    @Test
    void publishedEventContinuesTheTraceOfTheRequestThatWroteIt() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        Api api = new Api().withCategory();
        String id = as(api.seller).header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .body(api.productBody("Acme", sku("CAM-P", "P", "50"))).post("/products").then().statusCode(201)
                .extract().path("id");

        String stored = scalar(pool,
                "SELECT trace_parent FROM outbox_event WHERE message_key = $1 AND event_type = 'ProductCreated'",
                "trace_parent", String.class, id);
        assertNotNull(stored, "a outbox guarda o traceparent da requisição");
        assertTrue(stored.startsWith("00-" + traceId + "-"), stored);

        ConsumerRecord<String, String> record = consume(Kafka.PRODUCT_TOPIC, r -> id.equals(r.key()), 1).get(0);
        String published = header(record, "traceparent");
        assertNotNull(published, "a mensagem leva o traceparent");
        assertTrue(published.startsWith("00-" + traceId + "-"), published);
    }
}
