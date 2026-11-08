package dev.marketplace.cataloginventory;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static dev.marketplace.cataloginventory.Api.as;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class OperationalEndpointsTest {
    @Test
    void readinessIncludesReactiveDatabase() {
        given().get("/q/health/ready").then().statusCode(200)
                .body("status", is("UP")).body("checks.name", hasItem(containsString("Reactive PostgreSQL")));
    }

    @Test
    void livenessIsUp() {
        given().get("/q/health/live").then().statusCode(200).body("status", is("UP"));
    }

    @Test
    void exposesPrometheusMetrics() {
        given().get("/q/health/ready").then().statusCode(200);
        given().get("/q/metrics").then().statusCode(200).body(containsString("jvm_memory_used_bytes"));
    }

    @Test
    void sqlClientMetricsKeepAggregatesWithoutPerConnectionGauges() {
        as(TestTokens.seller(UUID.randomUUID(), UUID.randomUUID())).get("/products").then().statusCode(200);
        given().get("/q/metrics").then().statusCode(200)
                .body(containsString("postgresql_queue_delay_seconds_count"))
                .body(not(containsString("postgresql_queue_size")))
                .body(not(containsString("postgresql_current")));
    }

    @Test
    void serviceInfoIsPublic() {
        given().get("/internal/info").then().statusCode(200).body("context", is("catalog-inventory"));
    }

    @Test
    void unknownRouteReturnsNotFound() {
        given().get("/internal/nao-existe").then().statusCode(404);
    }
}
