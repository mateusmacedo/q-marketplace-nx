package dev.marketplace.cataloginventory;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static dev.marketplace.cataloginventory.Api.as;
import static dev.marketplace.cataloginventory.Api.sku;
import static org.hamcrest.Matchers.*;

/**
 * Catálogo de motivos de moderação como dado de cada tenant: o catalog-admin cria, revisa, ativa e desativa, com
 * If-Match; o moderador vê só os ativos, e as decisões passadas guardam o rótulo da época.
 */
@QuarkusTest
class ModerationReasonResourceTest {
  static final String PATH = "/moderation/reasons";

  @Test
  void adminCreatesAReasonWithLocationAndETagAndTheCodeIsUniqueInTheTenant() {
    Api api = new Api();
    String body = "{\"code\":\"counterfeit\",\"label\":\"Possível falsificação\",\"decisions\":[\"BLOCK\",\"REJECT\"]}";
    as(api.admin).body(body).post(PATH).then().statusCode(201)
        .header("Location", endsWith(PATH + "/counterfeit")).header("ETag", is("\"0\""))
        .body("code", is("counterfeit")).body("decisions", contains("REJECT", "BLOCK"))
        .body("position", is(0)).body("active", is(true)).body("version", is(0));
    as(api.admin).body(body).post(PATH).then().statusCode(409)
        .body("type", endsWith("/duplicate-moderation-reason-code"));
    as(api.admin).get(PATH + "/counterfeit").then().statusCode(200).header("ETag", is("\"0\""))
        .body("label", is("Possível falsificação"));

    as(api.admin).body("{\"code\":\"Bad Code\",\"label\":\"Rótulo\",\"decisions\":[\"REJECT\"]}").post(PATH)
        .then().statusCode(400).body("type", endsWith("/validation-failed"));
    as(api.admin).body("{\"code\":\"approval\",\"label\":\"Aprovação\",\"decisions\":[\"APPROVE\"]}").post(PATH)
        .then().statusCode(422).body("type", endsWith("/invalid-value"));
    as(api.admin).body("{\"code\":\"empty\",\"label\":\"Vazio\",\"decisions\":[]}").post(PATH).then()
        .statusCode(400);
    as(api.moderator).body(body.replace("counterfeit", "spam")).post(PATH).then().statusCode(403);
    as(api.seller).get(PATH + "/counterfeit").then().statusCode(403);
    as(api.moderator).get(PATH + "/counterfeit").then().statusCode(403);
  }

  @Test
  void revisionAndSituationChangesRequireTheCurrentVersion() {
    Api api = new Api().withModerationReasons();
    String path = PATH + "/prohibited-item";
    String revision = "{\"label\":\"Item proibido\",\"decisions\":[\"BLOCK\"],\"position\":0}";
    as(api.admin).body(revision).put(path).then().statusCode(428);
    as(api.admin).header("If-Match", "\"7\"").body(revision).put(path).then().statusCode(412);
    as(api.admin).header("If-Match", "\"0\"").body(revision).put(path).then().statusCode(200)
        .header("ETag", is("\"1\"")).body("code", is("prohibited-item")).body("label", is("Item proibido"))
        .body("position", is(0));
    as(api.admin).header("If-Match", "\"0\"").body(revision).put(PATH + "/missing").then().statusCode(404);

    api.post(api.admin, path + "/deactivate", null).then().statusCode(428);
    api.post(api.admin, path + "/deactivate", "\"0\"").then().statusCode(412);
    api.post(api.admin, path + "/deactivate", "\"1\"").then().statusCode(200).header("ETag", is("\"2\""))
        .body("active", is(false));
    api.post(api.admin, path + "/deactivate", "\"2\"").then().statusCode(200).header("ETag", is("\"2\""));
    api.post(api.moderator, path + "/activate", "\"2\"").then().statusCode(403);
    api.post(api.admin, path + "/activate", "\"2\"").then().statusCode(200).header("ETag", is("\"3\""))
        .body("active", is(true));
  }

  @Test
  void deactivatedReasonLeavesTheModeratorListAndIsRefusedInNewDecisions() {
    Api api = new Api().withCategory().withModerationReasons();
    api.post(api.admin, PATH + "/price-out-of-range/deactivate", "\"0\"").then().statusCode(200);
    as(api.moderator).get(PATH).then().statusCode(200).body("code", not(hasItem("price-out-of-range")));
    as(api.admin).queryParam("includeInactive", true).get(PATH).then().statusCode(200)
        .body("find { it.code == 'price-out-of-range' }.active", is(false));

    String id = api.createProduct(sku("INA-P", "P", "50"));
    api.submit(id).then().statusCode(200);
    as(api.moderator).body("{\"reasonCodes\":[\"price-out-of-range\"]}")
        .post("/moderation/products/" + id + "/reject").then().statusCode(422)
        .body("type", endsWith("/invalid-moderation-reason"))
        .body("reasons", contains("reason-inactive:price-out-of-range"))
        .body("reasonDetails[0].message",
            is("O motivo \"Preço fora do padrão\" foi desativado. Recarregue a lista de motivos."));
  }

  @Test
  void renamingAReasonKeepsTheLabelOfPastDecisions() {
    Api api = new Api().withCategory().withModerationReasons();
    String id = api.createProduct(sku("REN-P", "P", "50"));
    api.submit(id).then().statusCode(200);
    as(api.moderator).body("{\"reasonCodes\":[\"wrong-category\"]}").post("/moderation/products/" + id + "/reject")
        .then().statusCode(200);
    as(api.admin).header("If-Match", "\"0\"")
        .body("{\"label\":\"Categoria errada\",\"decisions\":[\"REJECT\"],\"position\":0}")
        .put(PATH + "/wrong-category").then().statusCode(200);
    as(api.seller).get("/products/" + id).then().statusCode(200)
        .body("lastRejection.reasonLabels[0].label", is("Categoria incorreta"))
        .body("lastRejection.reason", is("Categoria incorreta."));
    as(api.seller).get("/products/" + id + "/history").then().statusCode(200)
        .body("find { it.transition == 'REJECT' }.reasonLabels[0].label", is("Categoria incorreta"));
  }

  @Test
  void eachTenantKeepsItsOwnCatalog() {
    Api first = new Api().withModerationReasons();
    Api other = new Api().withCategory();
    as(other.moderator).get(PATH).then().statusCode(200).body("$", empty());
    as(other.admin).get(PATH + "/wrong-category").then().statusCode(404);
    other.reason("wrong-category", "Categoria errada", "REJECT");
    as(other.moderator).get(PATH).then().statusCode(200).body("label", contains("Categoria errada"));
    as(first.moderator).get(PATH).then().body("find { it.code == 'wrong-category' }.label",
        is("Categoria incorreta"));

    String id = other.createProduct(sku("TEN-P", "P", "50"));
    other.submit(id).then().statusCode(200);
    as(other.moderator).body("{\"reasonCodes\":[\"prohibited-item\"]}").post("/moderation/products/" + id + "/block")
        .then().statusCode(422).body("reasons", contains("unknown-reason:prohibited-item"));
  }
}
