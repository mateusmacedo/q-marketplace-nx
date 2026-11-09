package dev.marketplace.cataloginventory;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import static dev.marketplace.cataloginventory.Api.as;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Busca por texto com o plano genérico como padrão da sessão e uma conexão só no pool, para o teste enxergar os
 * statements que a API preparou e a conexão que ela ocupa.
 */
@QuarkusTest
@TestProfile(ProductSearchPlanTest.GenericPlans.class)
class ProductSearchPlanTest {
    public static class GenericPlans implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.datasource.reactive.max-size", "1",
                    "quarkus.datasource.reactive.additional-properties.plan_cache_mode", "force_generic_plan",
                    "catalog.events.relay-interval", "off",
                    "catalog.persistence.timeout", "1s");
        }
    }

    @Inject
    Pool pool;
    @Inject
    DataSource jdbc;

    @Test
    void textSearchRunsWithTheCustomPlanEvenAfterManyExecutions() {
        Api api = new Api().withCategory();
        api.createProduct(Api.sku("PLAN-1", "P", "10"));
        for (int run = 0; run < 7; run++)
            as(api.seller).queryParam("q", "plan").get("/products").then().statusCode(200).body("items", hasSize(1));
        Row plans = pool.query("""
                SELECT coalesce(sum(generic_plans), 0)::bigint AS generic, coalesce(sum(custom_plans), 0)::bigint AS custom
                FROM pg_prepared_statements WHERE statement LIKE '%lower(s.seller_sku) LIKE%'""").execute()
                .await().atMost(Duration.ofSeconds(20)).iterator().next();
        assertEquals(0L, plans.getLong("generic"));
        assertTrue(plans.getLong("custom") >= 7, "planos customizados: " + plans.getLong("custom"));
    }

    @Test
    void searchPastTheDeadlineIsCanceledInTheServerAndFreesTheConnection() throws SQLException {
        Api api = new Api().withCategory();
        api.createProduct(Api.sku("LOCK-1", "P", "10"));
        try (Connection lock = jdbc.getConnection()) {
            lock.setAutoCommit(false);
            try (Statement statement = lock.createStatement()) {
                statement.execute("LOCK TABLE catalog_inventory.product IN ACCESS EXCLUSIVE MODE");
            }
            as(api.seller).queryParam("q", "lock").get("/products").then().statusCode(503)
                    .body("type", endsWith("/service-unavailable"));
            assertEquals(1, pool.query("SELECT 1 AS one").execute().await().atMost(Duration.ofSeconds(5)).iterator()
                    .next().getInteger("one"));
            lock.rollback();
        }
    }
}
