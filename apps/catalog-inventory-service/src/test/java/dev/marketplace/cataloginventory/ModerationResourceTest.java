package dev.marketplace.cataloginventory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static dev.marketplace.cataloginventory.Api.as;
import static dev.marketplace.cataloginventory.Api.sku;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Moderação: fila em ordem de chegada, situação da loja para o moderador,
 * motivos prontos na rejeição e no bloqueio e decisões em lote.
 */
@QuarkusTest
class ModerationResourceTest {
    @Inject
    MeterRegistry registry;

    static String batch(String action, String extra, String... items) {
        return "{\"action\":\"%s\",\"items\":[%s]%s}".formatted(action, String.join(",", items), extra);
    }

    static String item(String id, Object version) {
        return version == null ? "{\"id\":\"" + id + "\"}" : "{\"id\":\"%s\",\"version\":%s}".formatted(id, version);
    }

    static List<String> queue(Api api, String cursor, int limit) {
        var request = as(api.moderator).queryParam("limit", limit);
        if (cursor != null)
            request = request.queryParam("cursor", cursor);
        return request.get("/moderation/products").then().statusCode(200).extract().path("items.id");
    }

    @Test
    void queueComesInOrderOfArrivalAndEditsInTheQueueKeepThePlace() {
        Api api = new Api().withCategory().approvedStore();
        String first = api.createProduct(sku("FIL-1", "P", "50"));
        String second = api.createProduct(sku("FIL-2", "P", "50"));
        String third = api.createProduct(sku("FIL-3", "P", "50"));
        api.stockAll(first, 5);
        for (String id : List.of(first, second, third))
            api.submit(id).then().statusCode(200).body("moderationQueuedAt", notNullValue());
        String path = "/products/" + first;
        as(api.seller).header("If-Match", api.etag(api.seller, path))
                .body("{\"categoryId\":\"%s\",\"title\":\"Camiseta revisada\",\"attributes\":{\"marca\":\"Acme\"}}"
                        .formatted(api.categoryId))
                .put(path).then().statusCode(200).body("status", is("PENDING_MODERATION"));
        assertEquals(List.of(first, second, third), queue(api, null, 10), "editar na fila não muda a chegada");

        JsonPath page = as(api.moderator).queryParam("limit", 2).get("/moderation/products").then().statusCode(200)
                .body("items[0].moderationQueuedAt", notNullValue()).extract().jsonPath();
        assertEquals(List.of(first, second), page.getList("items.id"));
        assertEquals(List.of(third), queue(api, page.getString("nextCursor"), 2));
        assertTrue(Instant.parse(page.getString("items[0].moderationQueuedAt"))
                .isBefore(Instant.parse(page.getString("items[1].moderationQueuedAt"))));

        api.approve(first).then().statusCode(200).body("moderationQueuedAt", nullValue());
        as(api.seller).header("If-Match", api.etag(api.seller, path))
                .body("{\"categoryId\":\"%s\",\"title\":\"Camiseta de novo\",\"attributes\":{\"marca\":\"Acme\"}}"
                        .formatted(api.categoryId))
                .put(path).then().statusCode(200).body("status", is("PENDING_MODERATION"));
        assertEquals(List.of(second, third, first), queue(api, null, 10), "a volta à moderação entra no fim");

        as(api.moderator).queryParam("cursor", "bm9wZQ").get("/moderation/products").then().statusCode(400);
    }

    @Test
    void moderatorReadsTheStoreEligibilityOfAnyStore() {
        Api api = new Api();
        as(api.moderator).get("/stores/" + api.store + "/eligibility").then().statusCode(200)
                .body("status", is("PENDING"));
        as(api.moderator).get("/stores/" + UUID.randomUUID() + "/eligibility").then().statusCode(200);
        as(TestTokens.reservations(api.tenant)).get("/stores/" + api.store + "/eligibility").then().statusCode(403);
    }

    @Test
    void moderatorReadsTheActiveReasonsOfTheTenantFilteredByDecision() {
        Api api = new Api();
        as(api.moderator).get("/moderation/reasons").then().statusCode(200).body("$", empty());
        api.withModerationReasons();
        as(api.moderator).get("/moderation/reasons").then().statusCode(200)
                .body("code", contains("wrong-category", "misleading-description", "inconsistent-attributes",
                        "price-out-of-range", "prohibited-item"))
                .body("find { it.code == 'wrong-category' }.label", is("Categoria incorreta"))
                .body("find { it.code == 'price-out-of-range' }.decisions", contains("REJECT", "BLOCK"))
                .body("active", everyItem(is(true)));
        as(api.moderator).queryParam("decision", "BLOCK").get("/moderation/reasons").then().statusCode(200)
                .body("code", contains("misleading-description", "price-out-of-range", "prohibited-item"));
        as(api.moderator).queryParam("decision", "PUBLISH").get("/moderation/reasons").then().statusCode(400);
        as(api.moderator).queryParam("includeInactive", true).get("/moderation/reasons").then().statusCode(403);
        as(api.seller).get("/moderation/reasons").then().statusCode(403);
    }

    @Test
    void rejectionRecordsCodesLabelsOfTheTimeAndNoteInHistoryAndLastRejection() {
        Api api = new Api().withCategory().withModerationReasons();
        String id = api.createProduct(sku("REJ-P", "P", "50"));
        api.submit(id).then().statusCode(200);
        String path = "/moderation/products/" + id + "/reject";
        as(api.moderator).body("{\"reasonCodes\":[\"prohibited-item\",\"spam\"]}").post(path).then().statusCode(422)
                .body("type", endsWith("/invalid-moderation-reason"))
                .body("reasons", contains("reason-not-applicable:prohibited-item", "unknown-reason:spam"))
                .body("reasonDetails[0].message", is("O motivo \"Item proibido na plataforma\" não vale para a rejeição."));
        as(api.moderator).body("{}").post(path).then().statusCode(422).body("type", endsWith("/invalid-value"));
        as(api.moderator).body("{\"note\":\"" + "x".repeat(501) + "\"}").post(path).then().statusCode(400)
                .body("type", endsWith("/validation-failed"));
        String note = "A gola não bate " + "x".repeat(484);
        as(api.moderator)
                .body("{\"reasonCodes\":[\"inconsistent-attributes\",\"wrong-category\"],\"note\":\"" + note + "\"}")
                .post(path).then().statusCode(200)
                .body("lastRejection.reasonCodes", contains("wrong-category", "inconsistent-attributes"))
                .body("lastRejection.reasonLabels.label",
                        contains("Categoria incorreta", "Atributos inconsistentes com os SKUs"))
                .body("lastRejection.note", is(note))
                .body("lastRejection.reason",
                        is("Categoria incorreta. Atributos inconsistentes com os SKUs. " + note));
        as(api.seller).get("/products/" + id + "/history").then().statusCode(200)
                .body("find { it.transition == 'REJECT' }.reasonCodes",
                        contains("wrong-category", "inconsistent-attributes"))
                .body("find { it.transition == 'REJECT' }.reasonLabels.code",
                        contains("wrong-category", "inconsistent-attributes"))
                .body("find { it.transition == 'REJECT' }.note", is(note))
                .body("find { it.transition == 'SUBMIT' }.reasonCodes", empty())
                .body("find { it.transition == 'SUBMIT' }.reasonLabels", empty())
                .body("find { it.transition == 'SUBMIT' }.note", nullValue())
                .body("find { it.transition == 'SUBMIT' }.actorSide", is("STORE"))
                .body("find { it.transition == 'SUBMIT' }.byCaller", is(true))
                .body("find { it.transition == 'REJECT' }.actorSide", is("MODERATION"))
                .body("find { it.transition == 'REJECT' }.byCaller", is(false))
                .body("actor", everyItem(nullValue()));
        as(api.moderator).get("/products/" + id + "/history").then().statusCode(200)
                .body("find { it.transition == 'REJECT' }.byCaller", is(true))
                .body("find { it.transition == 'SUBMIT' }.byCaller", is(false));
    }

    @Test
    void batchDecisionRunsEachItemAloneAndReportsFailuresInOrder() {
        Api api = new Api().withCategory().approvedStore();
        String fresh = api.createProduct(sku("LOT-1", "P", "50"));
        String stale = api.createProduct(sku("LOT-2", "P", "50"));
        String draft = api.createProduct(sku("LOT-3", "P", "50"));
        api.submit(fresh).then().statusCode(200);
        api.submit(stale).then().statusCode(200);
        String missing = UUID.randomUUID().toString();
        double approved = decisions("approved");
        as(api.moderator).body(batch("APPROVE", "", item(fresh, 1), item(stale, 0), item(draft, null),
                item(missing, null)))
                .post("/moderation/products/batch").then().statusCode(200)
                .body("results.id", contains(fresh, stale, draft, missing))
                .body("results.outcome", contains("SUCCEEDED", "FAILED", "FAILED", "FAILED"))
                .body("results[0].status", is("OUT_OF_STOCK")).body("results[0].version", is(2))
                .body("results[0].problem", nullValue())
                .body("results[1].problem.status", is(412)).body("results[1].problem.type", endsWith("/version-mismatch"))
                .body("results[1].status", nullValue())
                .body("results[2].problem.status", is(409))
                .body("results[2].problem.type", endsWith("/invalid-transition"))
                .body("results[3].problem.status", is(404));
        assertEquals(approved + 1, decisions("approved"), "só o item aprovado conta");
        as(api.seller).get("/products/" + stale).then().body("status", is("PENDING_MODERATION"));
    }

    @Test
    void batchBlockAppliesTheSameReasonToEveryItemAndHidesThemFromTheStorefront() {
        Api api = new Api().withCategory().withModerationReasons().approvedStore();
        String one = api.activeProduct(sku("BLQ-1", "P", "50"));
        String two = api.activeProduct(sku("BLQ-2", "M", "50"));
        api.storefront(one).then().statusCode(200);
        as(api.moderator).body(batch("BLOCK", ",\"reasonCodes\":[\"prohibited-item\"],\"note\":\"Réplica\"",
                item(one, null), item(two, null)))
                .post("/moderation/products/batch").then().statusCode(200)
                .body("results.outcome", everyItem(is("SUCCEEDED"))).body("results.status", everyItem(is("BLOCKED")));
        api.storefront(one).then().statusCode(404);
        api.storefront(two).then().statusCode(404);
        for (String id : List.of(one, two))
            as(api.moderator).get("/products/" + id + "/history").then().statusCode(200)
                    .body("find { it.transition == 'BLOCK' }.reason", is("Item proibido na plataforma. Réplica"))
                    .body("find { it.transition == 'BLOCK' }.reasonCodes", contains("prohibited-item"));
    }

    @Test
    void batchRequestIsRefusedWholeWhenItemsOrReasonAreInvalid() {
        Api api = new Api().withCategory().withModerationReasons();
        String id = api.createProduct(sku("REC-P", "P", "50"));
        api.submit(id).then().statusCode(200);
        String path = "/moderation/products/batch";
        as(api.moderator).body(batch("REJECT", ",\"note\":\"x\"", item(id, null), item(id, 1))).post(path).then()
                .statusCode(400).body("type", endsWith("/invalid-request")).body("detail", containsString(id));
        as(api.moderator).body(batch("REJECT", ",\"note\":\"x\"")).post(path).then().statusCode(400)
                .body("type", endsWith("/validation-failed"));
        String tooMany = IntStream.rangeClosed(0, 100).mapToObj(i -> item(UUID.randomUUID().toString(), null))
                .collect(Collectors.joining(","));
        as(api.moderator).body(batch("REJECT", ",\"note\":\"x\"", tooMany)).post(path).then().statusCode(400);
        as(api.moderator).body(batch("BLOCK", ",\"reasonCodes\":[\"wrong-category\"]", item(id, null))).post(path)
                .then().statusCode(422).body("reasons", contains("reason-not-applicable:wrong-category"));
        as(api.moderator).body(batch("APPROVE", ",\"note\":\"sem motivo\"", item(id, null))).post(path).then()
                .statusCode(400);
        as(api.moderator).body(batch("PUBLISH", "", item(id, null))).post(path).then().statusCode(400);
        as(api.seller).body(batch("APPROVE", "", item(id, null))).post(path).then().statusCode(403);
        as(api.seller).get("/products/" + id).then().body("status", is("PENDING_MODERATION")).body("version", is(1));
    }

    private double decisions(String decision) {
        Counter counter = registry.find("catalog.moderation.decisions").tag("decision", decision).counter();
        return counter == null ? 0 : counter.count();
    }
}
