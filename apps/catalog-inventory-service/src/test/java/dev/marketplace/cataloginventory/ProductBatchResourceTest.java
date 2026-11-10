package dev.marketplace.cataloginventory;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static dev.marketplace.cataloginventory.Api.as;
import static dev.marketplace.cataloginventory.Api.sku;
import static dev.marketplace.cataloginventory.ModerationResourceTest.batch;
import static dev.marketplace.cataloginventory.ModerationResourceTest.item;
import static org.hamcrest.Matchers.*;

/** Lote do seller: envio, pausa, retomada e arquivamento, cada produto isolado. */
@QuarkusTest
class ProductBatchResourceTest {
  @Test
  void eachProductRunsAloneWithTheRulesOfTheSingleEndpoint() {
    Api api = new Api().withCategory().approvedStore();
    String active = api.activeProduct(sku("SEL-1", "P", "50"));
    String draft = api.draftProduct(sku("SEL-2", "P", "50"));
    String other = as(api.otherStoreSeller()).body(api.productBody("Acme", sku("SEL-3", "P", "10")))
        .post("/products").then().statusCode(201).extract().path("id");
    as(api.seller).body(batch("PAUSE", "", item(active, 2), item(draft, null), item(other, null)))
        .post("/products/batch").then().statusCode(200)
        .body("results.id", contains(active, draft, other))
        .body("results.outcome", contains("SUCCEEDED", "FAILED", "FAILED"))
        .body("results[0].status", is("PAUSED_BY_SELLER")).body("results[0].version", is(3))
        .body("results[1].problem.type", endsWith("/invalid-transition"))
        .body("results[1].problem.status", is(409))
        .body("results[1].problem.title", is("Transição não permitida"))
        .body("results[2].problem.status", is(404));
    as(api.seller).get("/products/" + active + "/history").then()
        .body("transition", contains("CREATE", "SUBMIT", "APPROVE", "PAUSE"));

    api.stockAll(active, 0);
    as(api.seller).body(batch("RESUME", "", item(active, 3))).post("/products/batch").then().statusCode(200)
        .body("results[0].outcome", is("SUCCEEDED"))
        .body("results[0].status", is("OUT_OF_STOCK"));
    api.storefront(active).then().statusCode(200);
  }

  @Test
  void submitReportsEveryReasonAndArchiveTakesTheProductOut() {
    Api api = new Api().withCategory();
    String incomplete = api.draftProduct(sku("ENV-1", "P", "50"));
    String complete = api.createProduct(sku("ENV-2", "P", "50"));
    as(api.seller).body(batch("SUBMIT", "", item(incomplete, 0), item(complete, 0))).post("/products/batch")
        .then().statusCode(200)
        .body("results[0].problem.status", is(422))
        .body("results[0].problem.type", endsWith("/product-not-publishable"))
        .body("results[0].problem.reasons", contains("missing-offer:ENV-1"))
        .body("results[0].problem.reasonDetails[0].message", is("Defina o preço do SKU ENV-1."))
        .body("results[0].problem.instance", nullValue())
        .body("results[1].status", is("PENDING_MODERATION"));
    as(api.moderator).get("/moderation/products").then().body("items.id", contains(complete));
    as(api.seller).body(batch("ARCHIVE", "", item(incomplete, 0), item(complete, 0))).post("/products/batch")
        .then().statusCode(200)
        .body("results.outcome", contains("SUCCEEDED", "FAILED"))
        .body("results[0].status", is("ARCHIVED"))
        .body("results[1].problem.status", is(412));
  }

  @Test
  void requestIsRefusedWholeForInvalidItemsOrRole() {
    Api api = new Api().withCategory();
    String id = api.draftProduct(sku("REQ-1", "P", "50"));
    as(api.seller).body(batch("ARCHIVE", "", item(id, null), item(id, 0))).post("/products/batch").then()
        .statusCode(400).body("type", endsWith("/invalid-request"));
    as(api.seller).body(batch("ARCHIVE", "")).post("/products/batch").then().statusCode(400)
        .body("type", endsWith("/validation-failed"));
    as(api.seller).body(batch("DELETE", "", item(id, null))).post("/products/batch").then().statusCode(400);
    as(api.seller).body("{\"action\":\"ARCHIVE\",\"items\":[{\"version\":1}]}").post("/products/batch").then()
        .statusCode(400);
    as(api.moderator).body(batch("ARCHIVE", "", item(id, null))).post("/products/batch").then().statusCode(403);
    as(TestTokens.claims(api.tenant, "seller").sign()).body(batch("ARCHIVE", "", item(id, null)))
        .post("/products/batch").then().statusCode(403).body("type", endsWith("/access-denied"));
    as(api.seller).body(batch("ARCHIVE", "", item(UUID.randomUUID().toString(), null))).post("/products/batch")
        .then().statusCode(200).body("results[0].problem.status", is(404));
    as(api.seller).get("/products/" + id).then().body("status", is("DRAFT"));
  }
}
