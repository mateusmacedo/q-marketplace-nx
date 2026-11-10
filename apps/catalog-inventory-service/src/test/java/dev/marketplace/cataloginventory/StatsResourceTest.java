package dev.marketplace.cataloginventory;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import static dev.marketplace.cataloginventory.Api.as;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class StatsResourceTest {
    static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    @Inject
    Pool pool;

    static long sum(JsonPath stats, String field) {
        List<Integer> values = stats.getList("activity.points." + field);
        return values.stream().mapToLong(Integer::longValue).sum();
    }

    @Test
    void eachRoleReadsTheNumbersOfItsHomePage() {
        Api api = new Api().withCategory().approvedStore();
        api.activeProduct(Api.sku("STA-A", "P", "50"));
        String withoutStock = api.createProduct(Api.sku("STA-B", "P", "50"));
        api.submit(withoutStock).then().statusCode(200);
        api.approve(withoutStock).then().statusCode(200);
        api.draftProduct(Api.sku("STA-C", "P", "50"));
        String blocked = api.createProduct(Api.sku("STA-D", "P", "50"));
        api.submit(blocked).then().statusCode(200);
        as(api.moderator).body("{\"note\":\"Item proibido\"}")
                .post("/moderation/products/" + blocked + "/block").then().statusCode(200);
        api.submit(api.createProduct(Api.sku("STA-E", "P", "50"))).then().statusCode(200);

        JsonPath store = as(api.seller).queryParam("granularity", "day").get("/stats/store").then()
                .statusCode(200)
                .body("storeId", is(api.store.toString()))
                .body("counts.active", is(1)).body("counts.outOfStock", is(1))
                .body("counts.draft", is(1))
                .body("counts.pendingModeration", is(1)).body("counts.blocked", is(1))
                .body("counts.pausedBySeller", is(0)).body("counts.archived", is(0))
                .body("counts.total", is(5))
                .body("active.value", is(1)).body("draft.value", is(1)).body("blocked.value", is(1))
                .body("outOfStock.value", is(1)).body("outOfStock.previous", nullValue())
                .body("active.months", hasSize(7))
                .body("active.months[6].month",
                        is(LocalDate.now(SAO_PAULO).withDayOfMonth(1).toString()))
                .body("active.months[6].value", is(2)).body("active.previous", nullValue())
                .body("active.change", nullValue())
                .body("activity.granularity", is("day"))
                .body("activity.timeZone", is("America/Sao_Paulo"))
                .body("activity.points", hasSize(30))
                .body("activity.to", is(LocalDate.now(SAO_PAULO).toString()))
                .extract().jsonPath();
        assertEquals(4, sum(store, "submitted"));
        assertEquals(2, sum(store, "approved"));
        assertEquals(1, sum(store, "blocked"));

        JsonPath moderation = as(api.moderator).get("/stats/moderation").then().statusCode(200)
                .body("queue.value", is(1)).body("approved.value", is(2)).body("blocked.value", is(1))
                .body("rejected.value", is(0)).body("approved.months", hasSize(7))
                .body("activity.granularity", is("month")).body("activity.points", hasSize(12))
                .extract().jsonPath();
        assertEquals(2, sum(moderation, "approved"));
        as(api.moderator).get("/stats/moderation/queue").then().statusCode(200).body("count", is(1));
        as(TestTokens.moderator(UUID.randomUUID())).get("/stats/moderation/queue").then().statusCode(200)
                .body("count", is(0));

        as(api.admin).get("/stats/categories").then().statusCode(200).body("total", is(1)).body("roots", is(1))
                .body("inactive", is(0)).body("withProducts", is(1));

        as(api.moderator).get("/stats/store").then().statusCode(403);
        as(api.admin).get("/stats/store").then().statusCode(403);
        as(api.seller).get("/stats/moderation").then().statusCode(403);
        as(api.seller).get("/stats/moderation/queue").then().statusCode(403);
        as(api.admin).get("/stats/moderation/queue").then().statusCode(403);
        as(api.seller).get("/stats/categories").then().statusCode(403);
    }

    @Test
    void invalidPeriodIsRejected() {
        Api api = new Api();
        as(api.seller).queryParam("granularity", "week").get("/stats/store").then().statusCode(400)
                .body("type", endsWith("/invalid-request"));
        as(api.seller).queryParam("from", "2026-01-01").get("/stats/store").then().statusCode(400);
        as(api.seller).queryParam("from", "2026-13-01").queryParam("to", "2026-12-01").get("/stats/store")
                .then()
                .statusCode(400);
        as(api.moderator).queryParam("granularity", "day").queryParam("from", "2025-01-01")
                .queryParam("to", "2026-12-31").get("/stats/moderation").then().statusCode(400);
    }

    @Test
    void datesOutsideFourDigitYearsAreRejectedAndTheEdgesReachTheDatabase() {
        Api api = new Api();
        for (String[] period : List.of(new String[] { "year", "+294276-01-01" },
                new String[] { "day", "+999999999-12-31" }, new String[] { "year", "-5000-01-01" })) {
            as(api.seller).queryParam("granularity", period[0]).queryParam("from", period[1])
                    .queryParam("to", period[1]).get("/stats/store").then().statusCode(400)
                    .body("type", endsWith("/invalid-request"))
                    .body("detail", containsString("9999-12-31"));
            as(api.moderator).queryParam("granularity", period[0]).queryParam("from", period[1])
                    .queryParam("to", period[1]).get("/stats/moderation").then().statusCode(400);
        }
        as(api.seller).queryParam("granularity", "day").queryParam("from", "9999-12-31")
                .queryParam("to", "9999-12-31").get("/stats/store").then().statusCode(200)
                .body("activity.points.submitted", contains(0));
        as(api.moderator).queryParam("granularity", "year").queryParam("from", "0001-01-01")
                .queryParam("to", "0001-12-31").get("/stats/moderation").then().statusCode(200)
                .body("activity.points.period", contains("0001-01-01"));
    }

    @Test
    void bucketsFollowSaoPauloTime() {
        Api api = new Api().withCategory();
        String product = api.draftProduct(Api.sku("FUSO-P", "P", "50"));
        pool.preparedQuery(
                """
                        INSERT INTO product_status_change (id, product_id, tenant_id, store_id, transition, from_status, to_status,
                                                           actor, note, reason_codes, reason_labels, occurred_at)
                        VALUES ($1, $2, $3, $4, 'SUBMIT', 'DRAFT', 'PENDING_MODERATION', 'teste', NULL, '{}', '[]', $5)""")
                .execute(Tuple.of(UUID.randomUUID(), UUID.fromString(product), api.tenant, api.store,
                        OffsetDateTime.parse("2020-12-01T02:00:00Z")))
                .await().atMost(Duration.ofSeconds(20));
        as(api.seller).queryParam("granularity", "month").queryParam("from", "2020-11-15")
                .queryParam("to", "2020-12-15").get("/stats/store").then().statusCode(200)
                .body("activity.from", is("2020-11-01")).body("activity.to", is("2020-12-31"))
                .body("activity.points.period", contains("2020-11-01", "2020-12-01"))
                .body("activity.points.submitted", contains(1, 0));
        as(api.seller).queryParam("granularity", "day").queryParam("from", "2020-11-30")
                .queryParam("to", "2020-12-01").get("/stats/store").then().statusCode(200)
                .body("activity.points.submitted", contains(1, 0));
    }
}
