package dev.marketplace.cataloginventory;

import dev.marketplace.cataloginventory.domain.inventory.ReservationPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static dev.marketplace.cataloginventory.Api.as;
import static dev.marketplace.cataloginventory.Api.idFrom;
import static dev.marketplace.cataloginventory.Api.sku;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ReservationResourceTest {
        @Inject
        ReservationSweeper sweeper;
        @Inject
        Pool pool;

        /**
         * A ordem canônica de travamento do domínio coincide com a do tipo uuid do
         * PostgreSQL, que ordena a releitura dos itens; com a ordem do UUID do Java
         * (com sinal), reserva e liberação travariam os mesmos SKUs em ordens opostas.
         */
        @Test
        void lockOrderMatchesPostgresUuidOrder() {
                List<UUID> ids = List.of(UUID.fromString("80000000-0000-0000-0000-000000000000"),
                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                UUID.fromString("00000000-0000-0000-8000-000000000000"),
                                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                                UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"));
                List<UUID> byPostgres = new ArrayList<>();
                pool.preparedQuery("SELECT id FROM unnest($1::uuid[]) AS t(id) ORDER BY id")
                                .execute(Tuple.of(ids.toArray(UUID[]::new))).await().atMost(Duration.ofSeconds(10))
                                .forEach(row -> byPostgres.add(row.getUUID("id")));
                assertEquals(ids.stream().sorted(ReservationPolicy.LOCK_ORDER).toList(), byPostgres);
        }

        /** Produto ativo com SKU P (1 unidade, R$ 50) e SKU M (3 unidades, R$ 60). */
        static final class Scenario {
                final Api api = new Api().withCategory().approvedStore();
                final String product = api.activeProduct(sku("CAM-P", "P", "50"), sku("CAM-M", "M", "60"));
                final String p = api.skuId(product, "CAM-P"), m = api.skuId(product, "CAM-M");

                Scenario() {
                        api.stock(p, 1);
                        api.stock(m, 3);
                }

                String item(String sku, int quantity, String price) {
                        return "{\"skuId\":\"" + sku + "\",\"quantity\":" + quantity
                                        + ",\"expectedUnitPrice\":{\"amount\":" + price
                                        + ",\"currency\":\"BRL\"}}";
                }

                String body(String... items) {
                        return "{\"orderRef\":\"pedido-1\",\"items\":[" + String.join(",", items) + "]}";
                }

                ValidatableResponse reserve(String key, String body) {
                        return as(api.checkout).header("Idempotency-Key", key).body(body)
                                        .post("/inventory/reservations").then();
                }

                /** Reserva com uma chave de idempotência nova, como cada checkout distinto. */
                ValidatableResponse reserve(String body) {
                        return reserve(UUID.randomUUID().toString(), body);
                }

                ValidatableResponse inventory(String sku) {
                        return as(api.seller).get("/inventory/skus/" + sku).then().statusCode(200);
                }
        }

        @Test
        void reservesAndReplaysIdempotently() {
                Scenario s = new Scenario();
                String body = s.body(s.item(s.p, 1, "50"), s.item(s.m, 2, "60"));
                String location = s.reserve("chave-1", body).statusCode(201).body("status", is("ACTIVE"))
                                .body("items", hasSize(2))
                                .body("items.find { it.skuId == '" + s.m + "' }.unitPrice.amount", is(60.00f)).extract()
                                .header("Location");
                s.reserve("chave-1", body).statusCode(200).header("Idempotent-Replayed", is("true"))
                                .body("id", is(idFrom(location)));
                s.inventory(s.m).body("reserved", is(2)).body("available", is(1));
                s.reserve("chave-1", s.body(s.item(s.m, 1, "60"))).statusCode(422).body("type",
                                endsWith("/idempotency-key-reused"));
                as(s.api.checkout).body(body).post("/inventory/reservations").then().statusCode(400);
                as(s.api.checkout).get(location).then().statusCode(200).body("orderRef", is("pedido-1"));
                as(TestTokens.reservations(UUID.randomUUID())).get(location).then().statusCode(404);
        }

        @Test
        void lastUnitHasExactlyOneWinnerUnderConcurrency() throws Exception {
                Scenario s = new Scenario();
                int buyers = 10;
                List<Integer> statuses = Api.race(buyers,
                                attempt -> s.reserve(s.body(s.item(s.p, 1, "50"))).extract().response());
                assertEquals(1, statuses.stream().filter(code -> code == 201).count(), statuses.toString());
                assertEquals(buyers - 1, statuses.stream().filter(code -> code == 409).count(), statuses.toString());
                s.inventory(s.p).body("onHand", is(1)).body("reserved", is(1)).body("available", is(0));
        }

        @Test
        void reservationIsAllOrNothingAndListsMissingSkus() {
                Scenario s = new Scenario();
                s.reserve(s.body(s.item(s.p, 1, "50"), s.item(s.m, 5, "60")))
                                .statusCode(409)
                                .contentType("application/problem+json").body("type", endsWith("/insufficient-stock"))
                                .body("reasons", contains("insufficient-stock:" + s.m));
                s.inventory(s.p).body("reserved", is(0));
                s.inventory(s.m).body("reserved", is(0));
        }

        @Test
        void refusesChangedPriceAndUnavailableProduct() {
                Scenario s = new Scenario();
                s.reserve(s.body(s.item(s.p, 1, "49.90"))).statusCode(409)
                                .body("type", endsWith("/price-changed"))
                                .body("reasons", contains("price-changed:" + s.p));
                s.api.post(s.api.seller, "/products/" + s.product + "/pause", null).then().statusCode(200);
                s.reserve(s.body(s.item(s.p, 1, "50"))).statusCode(409)
                                .body("type", endsWith("/product-unavailable"));
                s.reserve(s.body(s.item(UUID.randomUUID().toString(), 1, "50")))
                                .statusCode(409)
                                .body("type", endsWith("/unknown-sku"));
        }

        @Test
        void commitConsumesOnceAndReleaseReturnsOnce() {
                Scenario s = new Scenario();
                String committed = s.reserve(s.body(s.item(s.m, 2, "60"))).statusCode(201)
                                .extract().path("id");
                as(s.api.checkout).post("/inventory/reservations/" + committed + "/commit").then().statusCode(200)
                                .body("status", is("COMMITTED")).body("closedAt", notNullValue());
                as(s.api.checkout).post("/inventory/reservations/" + committed + "/commit").then().statusCode(200)
                                .body("status", is("COMMITTED"));
                s.inventory(s.m).body("onHand", is(1)).body("reserved", is(0)).body("sold", is(2));
                as(s.api.checkout).post("/inventory/reservations/" + committed + "/release").then().statusCode(409)
                                .body("type", endsWith("/invalid-transition"));

                String released = s.reserve(s.body(s.item(s.m, 1, "60"))).statusCode(201)
                                .extract().path("id");
                as(s.api.checkout).post("/inventory/reservations/" + released + "/release").then().statusCode(200)
                                .body("status", is("RELEASED"));
                as(s.api.checkout).post("/inventory/reservations/" + released + "/release").then().statusCode(200)
                                .body("status", is("RELEASED"));
                s.inventory(s.m).body("onHand", is(1)).body("reserved", is(0)).body("available", is(1));
                as(s.api.checkout).post("/inventory/reservations/" + released + "/commit").then().statusCode(409)
                                .body("type", endsWith("/reservation-not-active"));
        }

        @Test
        void expiredReservationCannotBeCommittedAndSweeperReturnsStock() throws Exception {
                Scenario s = new Scenario();
                String id = as(s.api.checkout).header("Idempotency-Key", UUID.randomUUID().toString())
                                .body("{\"ttlSeconds\":1,\"items\":[" + s.item(s.m, 3, "60") + "]}")
                                .post("/inventory/reservations")
                                .then()
                                .statusCode(201).extract().path("id");
                s.inventory(s.m).body("available", is(0));
                Thread.sleep(1200);
                as(s.api.checkout).post("/inventory/reservations/" + id + "/commit").then().statusCode(409)
                                .body("type", endsWith("/reservation-expired"));
                sweeper.sweep().await().atMost(java.time.Duration.ofSeconds(10));
                as(s.api.checkout).get("/inventory/reservations/" + id).then().body("status", is("EXPIRED"));
                s.inventory(s.m).body("reserved", is(0)).body("available", is(3));
                as(s.api.checkout).post("/inventory/reservations/" + id + "/release").then().statusCode(200).body(
                                "status",
                                is("EXPIRED"));
                s.inventory(s.m).body("available", is(3));
        }

        @Test
        void extensionMovesTheDeadline() {
                Scenario s = new Scenario();
                String id = s.reserve(s.body(s.item(s.p, 1, "50"))).statusCode(201)
                                .extract()
                                .path("id");
                String before = as(s.api.checkout).get("/inventory/reservations/" + id).then().extract()
                                .path("expiresAt");
                String after = as(s.api.checkout).body("{\"ttlSeconds\":3600}")
                                .post("/inventory/reservations/" + id + "/extend").then()
                                .statusCode(200).extract().path("expiresAt");
                assertTrue(java.time.Instant.parse(after).isAfter(java.time.Instant.parse(before)));
                as(s.api.checkout).body("{\"ttlSeconds\":999999}").post("/inventory/reservations/" + id + "/extend")
                                .then()
                                .statusCode(422);
        }

        @Test
        void onlyReservationClientsAccessReservations() {
                Scenario s = new Scenario();
                as(s.api.seller).header("Idempotency-Key", "x").body(s.body(s.item(s.p, 1, "50")))
                                .post("/inventory/reservations").then().statusCode(403);
                given().header("Idempotency-Key", "x").contentType("application/json")
                                .body(s.body(s.item(s.p, 1, "50")))
                                .post("/inventory/reservations").then().statusCode(401);
                as(s.api.checkout).body("{\"items\":[]}").header("Idempotency-Key", "y").post("/inventory/reservations")
                                .then()
                                .statusCode(400);
                s.reserve(s.body(s.item(s.p, 1, "50"))).statusCode(201);
                given().get("/q/metrics").then().body(containsString("catalog_reservations_created_total"));
        }
}
