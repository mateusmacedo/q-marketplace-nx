package dev.marketplace.cataloginventory;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import static dev.marketplace.cataloginventory.Api.as;
import static dev.marketplace.cataloginventory.Api.sku;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class OfferResourceTest {
  @Test
  void firstPutCreatesTheOfferAndLaterPutsNeedIfMatch() {
    Api api = new Api().withCategory();
    String skuId = api.skuId(api.draftProduct(sku("CAM-P", "P", "50")), "CAM-P");
    String path = "/offers/" + skuId;
    as(api.seller).get(path).then().statusCode(404);
    as(api.seller).header("If-Match", "\"3\"").body(Api.price("50")).put(path).then().statusCode(412);
    as(api.seller).body(Api.price("50")).put(path).then().statusCode(201).header("ETag", is("\"0\""))
        .header("Location", endsWith(path))
        .body("currency", is("BRL")).body("price.salePrice.amount", is(50.00f))
        .body("effectivePrice.priceVersion", is(1));
    as(api.seller).body(Api.price("45")).put(path).then().statusCode(428);
    as(api.seller).header("If-Match", "\"0\"").body(Api.price("45")).put(path).then().statusCode(200)
        .header("ETag", is("\"1\""))
        .body("effectivePrice.salePrice.amount", is(45.00f))
        .body("effectivePrice.priceVersion", is(2));
    as(api.seller).header("If-Match", "\"1\"").body("{\"salePrice\":{\"amount\":45,\"currency\":\"USD\"}}")
        .put(path).then()
        .statusCode(422).body("type", endsWith("/currency-mismatch"));
    as(api.seller).header("If-Match", "\"1\"")
        .body("{\"listPrice\":{\"amount\":10,\"currency\":\"BRL\"},\"salePrice\":{\"amount\":70,\"currency\":\"BRL\"}}")
        .put(path).then().statusCode(422)
        .body("type", endsWith("/list-price-below-sale-price"));
  }

  @Test
  void priceAndPromotionKeepTheProductActive() {
    Api api = new Api().withCategory().approvedStore();
    String id = api.activeProduct(sku("CAM-P", "P", "50"));
    String path = "/offers/" + api.skuId(id, "CAM-P");
    as(api.seller).header("If-Match", api.etag(api.seller, path))
        .body("{\"listPrice\":{\"amount\":80,\"currency\":\"BRL\"},\"salePrice\":{\"amount\":70,\"currency\":\"BRL\"}}")
        .put(path).then().statusCode(200).body("effectivePrice.salePrice.amount", is(70.00f));
    Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
    String promotion = as(api.seller)
        .body("{\"salePrice\":{\"amount\":55,\"currency\":\"BRL\"},\"startsAt\":\"" + start
            + "\",\"endsAt\":\"" + start.plus(1, ChronoUnit.DAYS) + "\"}")
        .post(path + "/promotions").then().statusCode(201)
        .body("effectivePrice.salePrice.amount", is(55.00f))
        .body("effectivePrice.promotionId", notNullValue())
        .extract().header("Location");
    as(api.seller).get("/products/" + id).then().body("status", is("ACTIVE")).body("version", is(2));
    api.storefront(id).then().body("skus[0].price.salePrice.amount", is(55.00f));
    as(api.seller).delete(promotion).then().statusCode(200)
        .body("effectivePrice.salePrice.amount", is(70.00f))
        .body("effectivePrice.priceVersion", is(4));
  }

  @Test
  void onlyTheOwnerChangesAndTenantReadersSee() {
    Api api = new Api().withCategory();
    String skuId = api.skuId(api.createProduct(sku("CAM-P", "P", "50")), "CAM-P");
    as(api.otherStoreSeller()).header("If-Match", "\"0\"").body(Api.price("1"))
        .put("/offers/" + skuId)
        .then().statusCode(404);
    as(api.moderator).header("If-Match", "\"0\"").body(Api.price("1")).put("/offers/" + skuId).then()
        .statusCode(403);
    as(api.moderator).get("/offers/" + skuId).then().statusCode(200).body("skuId", is(skuId));
    as(api.otherTenantSeller()).get("/offers/" + skuId).then().statusCode(404);
  }

  @Test
  void archivedProductFreezesItsOffers() {
    Api api = new Api().withCategory();
    String id = api.createProduct(sku("CAM-P", "P", "50"));
    String path = "/offers/" + api.skuId(id, "CAM-P");
    as(api.seller).delete("/products/" + id).then().statusCode(200);
    as(api.seller).header("If-Match", "\"0\"").body(Api.price("40")).put(path).then().statusCode(409)
        .body("type", endsWith("/invalid-transition"));
  }

  @Test
  void concurrentPriceChangesWithSameVersionHaveOneWinner() throws Exception {
    Api api = new Api().withCategory();
    String id = api.createProduct(sku("CAM-P", "P", "50"));
    String path = "/offers/" + api.skuId(id, "CAM-P");
    List<Integer> statuses = Api.race(6, attempt -> as(api.seller).header("If-Match", "\"0\"")
        .body("{\"salePrice\":{\"amount\":" + (60 + attempt) + ",\"currency\":\"BRL\"}}")
        .put(path));
    assertEquals(1, statuses.stream().filter(s -> s == 200).count(), statuses.toString());
    assertTrue(statuses.stream().allMatch(s -> s == 200 || s == 409 || s == 412), statuses.toString());
    as(api.seller).get(path).then().body("version", is(1)).body("effectivePrice.priceVersion", is(2));
    as(api.seller).get("/products/" + id).then().body("version", is(0));
  }
}
