package dev.marketplace.cataloginventory;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static dev.marketplace.cataloginventory.Api.as;
import static dev.marketplace.cataloginventory.Api.sku;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Lista e leitura de produtos: busca, ordem com cursor composto, estado
 * efetivo, oferta e saldo junto dos SKUs, última rejeição e leitura pelo
 * moderador.
 */
@QuarkusTest
class ProductListingResourceTest {
  static String titled(Api api, String title, Api.SkuSpec... skus) {
    return as(api.seller).body(api.productBody("Acme", skus).replace("Camiseta básica", title))
        .post("/products").then().statusCode(201).extract().path("id");
  }

  /** Percorre todas as páginas de uma em uma e devolve os títulos na ordem recebida. */
  static List<String> titles(Api api, Map<String, String> params) {
    List<String> seen = new ArrayList<>();
    String cursor = null;
    do {
      var request = as(api.seller).queryParams(params).queryParam("limit", 1);
      if (cursor != null)
        request = request.queryParam("cursor", cursor);
      JsonPath page = request.get("/products").then().statusCode(200).extract().jsonPath();
      seen.addAll(page.getList("items.title"));
      cursor = page.getString("nextCursor");
    } while (cursor != null);
    return seen;
  }

  @Test
  void searchAndSortWalkEveryPageWithTheCompositeCursor() {
    Api api = new Api().withCategory();
    titled(api, "Caneca 300ml", sku("CAN-01", "P", "10"));
    titled(api, "Camiseta Básica", sku("CAM-P", "P", "10"));
    titled(api, "Árvore de Natal", sku("ARV-1", "P", "10"));
    assertEquals(List.of("Árvore de Natal", "Camiseta Básica", "Caneca 300ml"), titles(api, Map.of()));
    assertEquals(List.of("Árvore de Natal", "Camiseta Básica", "Caneca 300ml"),
        titles(api, Map.of("sort", "title")));
    assertEquals(List.of("Caneca 300ml", "Camiseta Básica", "Árvore de Natal"),
        titles(api, Map.of("sort", "-title")));
    assertEquals(List.of("Caneca 300ml", "Camiseta Básica", "Árvore de Natal"),
        titles(api, Map.of("sort", "createdAt")));
    assertEquals(List.of("Camiseta Básica"), titles(api, Map.of("q", "CAMISÉTA basica")));
    assertEquals(List.of("Caneca 300ml"), titles(api, Map.of("q", "can-0")));
    assertEquals(List.of("Caneca 300ml", "Camiseta Básica"), titles(api, Map.of("q", "ca", "sort", "-title")));
    assertEquals(List.of(), titles(api, Map.of("q", "100%_")));
  }

  @Test
  void cursorOfAnotherSortOrSearchIsRefused() {
    Api api = new Api().withCategory();
    titled(api, "A1");
    titled(api, "A2");
    String cursor = as(api.seller).queryParam("sort", "title").queryParam("limit", 1).get("/products")
        .then().statusCode(200).extract().path("nextCursor");
    as(api.seller).queryParam("sort", "-title").queryParam("cursor", cursor).get("/products").then()
        .statusCode(400).body("detail", containsString("outra ordem ou outra busca"));
    as(api.seller).queryParam("sort", "title").queryParam("q", "a").queryParam("cursor", cursor)
        .get("/products").then().statusCode(400);
    as(api.seller).queryParam("sort", "title").queryParam("cursor", cursor).get("/products").then()
        .statusCode(200).body("items.title", contains("A2")).body("nextCursor", nullValue());
  }

  @Test
  void nullCharacterInSearchOrCursorIsRefusedBeforeTheDatabase() {
    Api api = new Api().withCategory();
    titled(api, "A1");
    titled(api, "A2");
    as(api.seller).queryParam("q", "a\u0000").get("/products").then().statusCode(400)
        .body("type", endsWith("/invalid-request")).body("detail", containsString("U+0000"));
    String cursor = as(api.seller).queryParam("sort", "title").queryParam("limit", 1).get("/products")
        .then().statusCode(200).extract().path("nextCursor");
    String[] key = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
        .split("\\|", 4);
    key[3] = "a\u0000";
    as(api.seller).queryParam("sort", "title").queryParam("cursor", encode(String.join("|", key)))
        .get("/products").then().statusCode(400).body("detail", is("cursor inválido"));
    as(api.seller).queryParam("cursor", encode("a\u0000")).get("/categories").then().statusCode(400)
        .body("detail", is("cursor inválido"));
  }

  static String encode(String key) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void outOfStockIsFilteredByTheBalanceAndSkusCarryOfferAndStock() {
    Api api = new Api().withCategory().approvedStore();
    String id = api.activeProduct(sku("CAM-P", "P", "50"));
    String skuId = api.skuId(id, "CAM-P");
    as(api.seller).queryParam("status", "ACTIVE").get("/products").then().statusCode(200)
        .body("items.id", contains(id))
        .body("items[0].skus[0].offer.currency", is("BRL"))
        .body("items[0].skus[0].offer.listPrice.amount", is(120.00f))
        .body("items[0].skus[0].offer.salePrice.amount", is(50.00f))
        .body("items[0].skus[0].offer.effectivePrice.amount", is(50.00f))
        .body("items[0].skus[0].offer.onPromotion", is(false))
        .body("items[0].skus[0].offer.version", is(0))
        .body("items[0].skus[0].stock.onHand", is(10))
        .body("items[0].skus[0].stock.available", is(10))
        .body("items[0].skus[0].stock.reserved", is(0))
        .body("items[0].skus[0].stock.sold", is(0));
    as(api.seller).queryParam("status", "OUT_OF_STOCK").get("/products").then().statusCode(200)
        .body("items", empty());
    api.stock(skuId, 0);
    as(api.seller).queryParam("status", "OUT_OF_STOCK").get("/products").then().statusCode(200)
        .body("items.id", contains(id)).body("items[0].status", is("OUT_OF_STOCK"));
    as(api.seller).queryParam("status", "ACTIVE").get("/products").then().statusCode(200)
        .body("items", empty());
    Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
    as(api.seller).body("{\"salePrice\":{\"amount\":45,\"currency\":\"BRL\"},\"startsAt\":\"" + start
        + "\",\"endsAt\":\"" + start.plus(1, ChronoUnit.DAYS) + "\"}")
        .post("/offers/" + skuId + "/promotions").then().statusCode(201);
    as(api.seller).get("/products/" + id).then().statusCode(200)
        .body("skus[0].offer.salePrice.amount", is(50.00f))
        .body("skus[0].offer.effectivePrice.amount", is(45.00f))
        .body("skus[0].offer.onPromotion", is(true))
        .body("skus[0].stock.onHand", is(0));
    as(api.seller).get("/products/" + id + "/skus/" + skuId).then().statusCode(200)
        .body("offer.effectivePrice.amount", is(45.00f)).body("stock.available", is(0));
    String bare = api.draftProduct(sku("BON-P", "P", "10"));
    as(api.seller).get("/products/" + bare).then().statusCode(200)
        .body("skus[0].offer", nullValue()).body("skus[0].stock.onHand", is(0));
  }

  @Test
  void draftReturnedByModerationCarriesTheLastRejection() {
    Api api = new Api().withCategory();
    String rejected = api.createProduct(sku("CAM-P", "P", "50"));
    String fresh = api.createProduct(sku("CAM-M", "M", "50"));
    api.submit(rejected).then().statusCode(200);
    as(api.moderator).body("{\"note\":\"Foto ilegível\"}")
        .post("/moderation/products/" + rejected + "/reject").then().statusCode(200)
        .body("lastRejection.reason", is("Foto ilegível"));
    as(api.seller).get("/products").then().statusCode(200)
        .body("items.find { it.id == '" + rejected + "' }.lastRejection.reason",
            is("Foto ilegível"))
        .body("items.find { it.id == '" + rejected + "' }.lastRejection.actor",
            not(emptyOrNullString()))
        .body("items.find { it.id == '" + rejected + "' }.lastRejection.at", notNullValue())
        .body("items.find { it.id == '" + fresh + "' }.lastRejection", nullValue());
    as(api.seller).get("/products/" + rejected).then().body("lastRejection.reason", is("Foto ilegível"));
    api.submit(rejected).then().statusCode(200).body("lastRejection", nullValue());
  }

  @Test
  void moderatorReadsEveryStoreAndUnblocksOutsideTheQueue() {
    Api api = new Api().withCategory().approvedStore();
    String id = api.activeProduct(sku("CAM-P", "P", "50"));
    String skuId = api.skuId(id, "CAM-P");
    String other = as(api.otherStoreSeller()).body(api.productBody("Acme", sku("Z1", "P", "10")))
        .post("/products").then().statusCode(201).extract().path("id");
    as(api.moderator).get("/products").then().statusCode(200).body("items.id", hasItems(id, other));
    as(api.moderator).queryParam("storeId", api.store.toString()).get("/products").then().statusCode(200)
        .body("items.id", contains(id));
    as(api.moderator).get("/products/" + id).then().statusCode(200)
        .body("skus[0].offer.salePrice.amount", is(50.00f)).body("skus[0].stock.onHand", is(10));
    as(api.moderator).get("/offers/" + skuId).then().statusCode(200);
    as(api.moderator).get("/inventory/skus/" + skuId).then().statusCode(200);
    as(api.seller).get("/products/" + other).then().statusCode(404);
    as(api.seller).queryParam("storeId", UUID.randomUUID().toString()).get("/products").then()
        .statusCode(200).body("items.id", contains(id));
    as(api.moderator).body("{\"note\":\"Item proibido\"}").post("/moderation/products/" + id + "/block")
        .then().statusCode(200);
    as(api.moderator).get("/moderation/products").then().statusCode(200).body("items.id", not(hasItem(id)));
    as(api.moderator).get("/products/" + id + "/history").then().statusCode(200)
        .body("transition", hasItem("BLOCK"));
    as(api.moderator).post("/moderation/products/" + id + "/unblock").then().statusCode(200)
        .body("status", is("PENDING_MODERATION"));
    as(api.moderator).get("/moderation/products").then().statusCode(200).body("items.id", hasItem(id))
        .body("items.find { it.id == '" + id + "' }.skus[0].offer.salePrice.amount",
            is(50.00f));
  }
}
