package dev.marketplace.cataloginventory;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static dev.marketplace.cataloginventory.Api.as;
import static dev.marketplace.cataloginventory.Api.sku;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Ações permitidas, pendências de publicação e a checagem do rascunho vêm do
 * servidor, calculadas pelas mesmas regras que os endpoints de escrita
 * aplicam.
 */
@QuarkusTest
class ProductActionsResourceTest {
  static final String PANTS = """
      {"code":"calcas","name":"Calças","attributes":[
        {"code":"marca","label":"Marca","type":"TEXT","required":true,"variant":false},
        {"code":"cintura","label":"Cintura (cm)","type":"INTEGER","required":true,"variant":true}]}""";

  static JsonPath product(String token, String id) {
    return as(token).get("/products/" + id).then().statusCode(200).extract().jsonPath();
  }

  @Test
  void draftDetailBringsActionsAndPublishingIssuesWithTheField() {
    Api api = new Api().withCategory();
    String id = api.draftProduct(sku("CAM-P", "P", "50"));

    JsonPath draft = product(api.seller, id);
    assertEquals(List.of("EDIT", "CHANGE_CATEGORY", "ADD_SKU", "REMOVE_SKU", "EDIT_OFFER", "EDIT_STOCK",
        "ARCHIVE"), draft.getList("actions"));
    assertEquals("SUBMIT", draft.getString("blockedActions[0].action"));
    assertEquals("product-not-publishable", draft.getString("blockedActions[0].reason.code"));
    assertEquals("O produto ainda tem pendências para ser publicado.",
        draft.getString("blockedActions[0].reason.message"));
    assertFalse(draft.getBoolean("saveReturnsToModeration"));
    assertEquals(1, draft.getList("publishingIssues").size());
    assertEquals(Map.of("code", "missing-offer", "params", List.of("CAM-P"), "message",
        "Defina o preço do SKU CAM-P.", "field", "skus[CAM-P].offer"),
        draft.getMap("publishingIssues[0]"));

    api.offer(api.skuId(id, "CAM-P"), "50");
    JsonPath complete = product(api.seller, id);
    assertTrue(complete.getList("publishingIssues").isEmpty());
    assertTrue(complete.getList("actions").contains("SUBMIT"));
    assertTrue(complete.getList("blockedActions").isEmpty());
  }

  @Test
  void listItemsCarryActionsWithoutReadingThePublishingIssues() {
    Api api = new Api().withCategory();
    api.draftProduct(sku("CAM-P", "P", "50"));
    as(api.seller).get("/products").then().statusCode(200)
        .body("items[0].actions", hasItem("SUBMIT"))
        .body("items[0].blockedActions", empty())
        .body("items[0].publishingIssues", nullValue());
    as(api.admin).get("/products").then().statusCode(200)
        .body("items[0].actions", empty())
        .body("items[0].blockedActions", empty());
  }

  @Test
  void moderationQueueShowsApprovalBlockedUntilTheStoreIsApproved() {
    Api api = new Api().withCategory();
    String id = api.createProduct(sku("CAM-P", "P", "50"));
    api.submit(id).then().statusCode(200);

    as(api.moderator).get("/moderation/products").then().statusCode(200)
        .body("items[0].actions", contains("REJECT", "BLOCK"))
        .body("items[0].blockedActions[0].action", is("APPROVE"))
        .body("items[0].blockedActions[0].reason.code", is("seller-not-eligible"))
        .body("items[0].blockedActions[0].reason.params", contains("PENDING"))
        .body("items[0].blockedActions[0].reason.message", is("A loja ainda não foi aprovada no KYB/KYC."));
    api.approve(id).then().statusCode(422).body("type", endsWith("/seller-not-eligible"));

    api.approvedStore();
    as(api.moderator).get("/moderation/products").then().statusCode(200)
        .body("items[0].actions", contains("APPROVE", "REJECT", "BLOCK"))
        .body("items[0].blockedActions", empty());
    api.approve(id).then().statusCode(200).body("actions", contains("BLOCK"))
        .body("saveReturnsToModeration", is(false));
  }

  @Test
  void publishedProductTakesNewSkusReturnsToModerationAndExplainsTheBlockedResume() {
    Api api = new Api().withCategory().approvedStore();
    String id = api.activeProduct(sku("CAM-P", "P", "50"));
    JsonPath active = product(api.seller, id);
    assertTrue(active.getBoolean("saveReturnsToModeration"));
    assertTrue(active.getList("actions").containsAll(List.of("ADD_SKU", "PAUSE", "ARCHIVE")));
    assertEquals("REMOVE_SKU", active.getString("blockedActions[0].action"));
    assertEquals("sku-removal-requires-draft", active.getString("blockedActions[0].reason.code"));

    api.post(api.seller, "/products/" + id + "/pause", null).then().statusCode(200)
        .body("actions", hasItem("RESUME"));
    api.kybVerdict("SellerRejected", "REJECTED");
    as(api.seller).get("/products/" + id).then().statusCode(200)
        .body("actions", not(hasItem("RESUME")))
        .body("blockedActions.find { it.action == 'RESUME' }.reason.code", is("seller-not-eligible"))
        .body("blockedActions.find { it.action == 'RESUME' }.reason.params", contains("REJECTED"));
    api.post(api.seller, "/products/" + id + "/resume", null).then().statusCode(422)
        .body("type", endsWith("/seller-not-eligible"));
  }

  @Test
  void checkEvaluatesTheUnsavedDraftAndTheCategoryChangeWithoutWriting() {
    Api api = new Api().withCategory();
    String id = api.createProduct(sku("CAM-P", "P", "50"), sku("CAM-M", "M", "50"));
    String etag = api.etag(api.seller, "/products/" + id);

    as(api.seller).body("{\"categoryId\":\"%s\",\"attributes\":{\"cor\":\"azul\"}}".formatted(api.categoryId))
        .post("/products/" + id + "/check").then().statusCode(200)
        .body("publishingIssues.code", contains("unknown-attribute", "missing-attribute"))
        .body("publishingIssues.field", contains("attributes.cor", "attributes.marca"))
        .body("publishingIssues[1].message", is("Preencha o atributo Marca."))
        .body("categoryChange", nullValue());

    String pants = as(api.admin).body(PANTS).post("/categories").then().statusCode(201).extract().path("id");
    JsonPath check = as(api.seller)
        .body("{\"categoryId\":\"%s\",\"attributes\":{\"marca\":\"Acme\",\"gola\":\"V\"}}".formatted(pants))
        .post("/products/" + id + "/check").then().statusCode(200).extract().jsonPath();
    assertEquals("gola", check.getString("categoryChange.removedAttributes[0].code"));
    assertEquals("V", check.getString("categoryChange.removedAttributes[0].value"));
    assertEquals("unknown-attribute", check.getString("categoryChange.removedAttributes[0].reason.code"));
    assertEquals(List.of(Map.of("code", "cintura", "label", "Cintura (cm)", "variant", true)),
        check.getList("categoryChange.newRequired"));
    assertEquals(List.of("CAM-P", "CAM-M"), check.getList("categoryChange.blockingSkus.sellerSku"));
    assertEquals("O SKU CAM-P usa tamanho, que a categoria escolhida não tem como variação.",
        check.getString("categoryChange.blockingSkus[0].reasons[0].message"));
    assertTrue(check.getList("publishingIssues.field").contains("skus[CAM-P].variantAttributes.cintura"));

    assertEquals(etag, api.etag(api.seller, "/products/" + id), "a checagem não grava");
    as(api.seller).get("/products/" + id).then().body("categoryId", is(api.categoryId))
        .body("attributes", is(Map.of("marca", "Acme")));
  }

  @Test
  void checkHasTheReachOfTheEdit() {
    Api api = new Api().withCategory();
    String id = api.createProduct(sku("CAM-P", "P", "50"));
    String body = "{\"categoryId\":\"%s\",\"attributes\":{}}".formatted(api.categoryId);
    as(api.otherStoreSeller()).body(body).post("/products/" + id + "/check").then().statusCode(404);
    as(api.moderator).body(body).post("/products/" + id + "/check").then().statusCode(403);
    as(api.seller).body("{\"attributes\":{}}").post("/products/" + id + "/check").then().statusCode(400);
    as(api.seller).body("{\"categoryId\":\"%s\"}".formatted(UUID.randomUUID()))
        .post("/products/" + id + "/check").then().statusCode(404);
    api.submit(id).then().statusCode(200);
    as(api.moderator).body("{\"note\":\"Produto proibido\"}").post("/moderation/products/" + id + "/block")
        .then().statusCode(200).body("actions", contains("UNBLOCK"));
    as(api.seller).body(body).post("/products/" + id + "/check").then().statusCode(409)
        .body("type", endsWith("/invalid-transition"));
  }

  @Test
  void eligibilityStatesItsEffects() {
    Api api = new Api();
    as(api.seller).get("/stores/" + api.store + "/eligibility").then().statusCode(200)
        .body("status", is("PENDING")).body("canPublish", is(false))
        .body("visibleInStorefront", is(false));
    api.approvedStore();
    as(api.seller).get("/stores/" + api.store + "/eligibility").then().statusCode(200)
        .body("canPublish", is(true)).body("visibleInStorefront", is(true));
  }
}
