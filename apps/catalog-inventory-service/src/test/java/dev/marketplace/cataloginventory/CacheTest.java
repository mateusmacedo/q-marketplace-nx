package dev.marketplace.cataloginventory;

import dev.marketplace.cataloginventory.cache.CacheContents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import static dev.marketplace.cataloginventory.Api.as;
import static dev.marketplace.cataloginventory.Api.sku;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cache da vitrine, da taxonomia e dos motivos de moderação (ADR 024): a leitura repetida vem do Redis, cada escrita no
 * produto, na oferta, na loja, na categoria ou no motivo aparece na leitura seguinte, e o estoque segue lido do banco
 * com o produto em cache.
 */
@QuarkusTest
class CacheTest {
  static final String REQUESTS = "catalog.cache.requests";

  @Inject
  MeterRegistry registry;

  @Inject
  Redis redis;

  /** A resposta da falta não espera a gravação; assim que o retrato chega ao Redis, a leitura seguinte acerta. */
  @Test
  void secondStorefrontReadComesFromTheCache() {
    Api api = new Api().withCategory().approvedStore();
    String id = api.activeProduct(sku("CCH-P", "P", "50"));
    double misses = requests("storefront", "miss");
    double writes = count("catalog.cache.writes", "storefront", "ok");
    api.storefront(id).then().statusCode(200);
    assertEquals(misses + 1, requests("storefront", "miss"), "a primeira leitura carrega do banco e guarda");
    awaitStored(CacheContents.storefront(api.tenant, id));
    // WHY: a chave aparece no Redis antes de a resposta do SET voltar ao cliente, que só então conta.
    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(writes + 1,
        count("catalog.cache.writes", "storefront", "ok"), "a gravação é contada"));
    cachedRead(api, id).body("skus[0].price.salePrice.amount", is(50.00f)).body("status", is("ACTIVE"));
    given().get("/q/metrics").then().statusCode(200)
        .body(containsString("catalog_cache_requests_total{cache=\"storefront\",result=\"hit\""));
  }

  @Test
  void moderatorBlockTakesTheCachedProductOffTheStorefront() {
    Api api = new Api().withCategory().approvedStore();
    String id = api.activeProduct(sku("CCH-P", "P", "50"));
    loadedRead(api, id);
    cachedRead(api, id);
    as(api.moderator).body("{\"note\":\"Produto proibido\"}").post("/moderation/products/" + id + "/block")
        .then().statusCode(200);
    api.storefront(id).then().statusCode(404);
  }

  @Test
  void newPriceAndPromotionsShowOnTheNextRead() {
    Api api = new Api().withCategory().approvedStore();
    String id = api.activeProduct(sku("CCH-P", "P", "50"));
    String path = "/offers/" + api.skuId(id, "CCH-P");
    loadedRead(api, id);
    cachedRead(api, id).body("skus[0].price.salePrice.amount", is(50.00f));

    as(api.seller).header("If-Match", api.etag(api.seller, path)).body(Api.price("45")).put(path).then()
        .statusCode(200);
    api.storefront(id).then().body("skus[0].price.salePrice.amount", is(45.00f));

    Instant now = Instant.now();
    as(api.seller).body(promotion("40", now.minus(1, ChronoUnit.MINUTES), now.plus(1, ChronoUnit.DAYS)))
        .post(path + "/promotions").then().statusCode(201);
    api.storefront(id).then().body("skus[0].price.salePrice.amount", is(40.00f))
        .body("skus[0].price.promotionId", notNullValue());
  }

  /** O retrato guarda as promoções e o preço vigente sai do relógio da leitura: a virada dispensa invalidação. */
  @Test
  void scheduledPromotionStartsWhileTheProductIsCached() {
    Api api = new Api().withCategory().approvedStore();
    String id = api.activeProduct(sku("CCH-P", "P", "50"));
    String path = "/offers/" + api.skuId(id, "CCH-P");
    Instant start = Instant.now().plusSeconds(3);
    as(api.seller).body(promotion("30", start, start.plus(1, ChronoUnit.DAYS))).post(path + "/promotions").then()
        .statusCode(201);
    loadedRead(api, id).body("skus[0].price.salePrice.amount", is(50.00f));
    cachedRead(api, id).body("skus[0].price.salePrice.amount", is(50.00f));
    Instant started = start.plusMillis(200);
    await().atMost(Kafka.WAIT).until(() -> Instant.now().isAfter(started));
    cachedRead(api, id).body("skus[0].price.salePrice.amount", is(30.00f));
  }

  @Test
  void rejectedStoreLeavesTheStorefrontWithAllItsProducts() {
    Api api = new Api().withCategory().approvedStore();
    String first = api.activeProduct(sku("CCH-A", "P", "50"));
    String second = api.activeProduct(sku("CCH-B", "M", "60"));
    for (String id : new String[]{first, second}) {
      loadedRead(api, id);
      cachedRead(api, id);
    }
    api.kybVerdict("SellerRejected", "REJECTED");
    // WHY: o veredito chega pelo Kafka, e a elegibilidade lida do banco aparece um instante antes da invalidação
    // que segue o commit; a espera fica bem abaixo da validade do conteúdo (60 s).
    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
      api.storefront(first).then().statusCode(404);
      api.storefront(second).then().statusCode(404);
    });
  }

  @Test
  void stockIsReadFromTheDatabaseWhileTheProductIsCached() {
    ReservationResourceTest.Scenario s = new ReservationResourceTest.Scenario();
    loadedRead(s.api, s.product);
    cachedRead(s.api, s.product).body("skus.find { it.id == '" + s.p + "' }.inStock", is(true));
    s.reserve(s.body(s.item(s.p, 1, "50"))).statusCode(201);
    cachedRead(s.api, s.product).body("status", is("ACTIVE"))
        .body("skus.find { it.id == '" + s.p + "' }.inStock", is(false))
        .body("skus.find { it.id == '" + s.m + "' }.inStock", is(true));
  }

  @Test
  void newCategoryAndRevisionShowOnTheNextTaxonomyRead() {
    Api api = new Api().withCategory();
    as(api.admin).get("/categories").then().statusCode(200).body("items.code", contains("camisetas"));
    awaitStored(CacheContents.rootPage(api.tenant));
    double hits = requests("taxonomy", "hit");
    as(api.admin).get("/categories").then().statusCode(200).body("items.code", contains("camisetas"));
    assertEquals(hits + 1, requests("taxonomy", "hit"), "a página de raízes vem do cache");
    as(api.admin).body("{\"code\":\"bones\",\"name\":\"Bonés\",\"attributes\":[]}").post("/categories").then()
        .statusCode(201);
    as(api.admin).get("/categories").then().statusCode(200).body("items.code", contains("bones", "camisetas"));

    String path = "/categories/" + api.categoryId;
    as(api.admin).get(path).then().statusCode(200).body("name", is("Camisetas"));
    awaitStored(CacheContents.chain(api.tenant, api.categoryId));
    hits = requests("taxonomy", "hit");
    as(api.admin).get(path).then().statusCode(200).body("name", is("Camisetas"));
    assertEquals(hits + 1, requests("taxonomy", "hit"), "a cadeia da categoria vem do cache");
    as(api.admin).header("If-Match", "\"0\"")
        .body("{\"name\":\"Camisetas e regatas\",\"active\":true,\"attributes\":[]}").put(path).then()
        .statusCode(200);
    as(api.admin).get(path).then().statusCode(200).body("name", is("Camisetas e regatas")).body("version", is(1));

    String caps = as(api.admin).get("/categories").then().extract().path("items.find { it.code == 'bones' }.id");
    as(api.admin).header("If-Match", "\"0\"").delete("/categories/" + caps).then().statusCode(204);
    as(api.admin).get("/categories").then().statusCode(200).body("items.code", contains("camisetas"));
    as(api.admin).get("/categories/" + caps).then().statusCode(404);
  }

  /** O catálogo de motivos vem do cache, e desativar um motivo vale já na leitura e na decisão seguintes. */
  @Test
  void deactivatedReasonLeavesTheCachedCatalogAndTheNextDecision() {
    Api api = new Api().withCategory().withModerationReasons();
    as(api.moderator).get("/moderation/reasons").then().statusCode(200).body("code", hasItem("wrong-category"));
    awaitStored(CacheContents.moderationReasons(api.tenant));
    double hits = requests("moderation-reasons", "hit");
    as(api.moderator).get("/moderation/reasons").then().statusCode(200).body("code", hasItem("wrong-category"));
    assertEquals(hits + 1, requests("moderation-reasons", "hit"), "o catálogo vem do cache");

    api.post(api.admin, "/moderation/reasons/wrong-category/deactivate", "\"0\"").then().statusCode(200);
    as(api.moderator).get("/moderation/reasons").then().statusCode(200)
        .body("code", not(hasItem("wrong-category")));
    String id = api.createProduct(sku("CCH-R", "P", "50"));
    api.submit(id).then().statusCode(200);
    as(api.moderator).body("{\"reasonCodes\":[\"wrong-category\"]}").post("/moderation/products/" + id + "/reject")
        .then().statusCode(422).body("reasons", contains("reason-inactive:wrong-category"));
  }

  /**
   * Os comandos e o pool do cliente Redis seguem medidos, sem os gauges por conexão, que o Micrometer recusaria a
   * partir da segunda conexão com um aviso.
   */
  @Test
  void redisClientMetricsKeepTheTimersWithoutPerConnectionGauges() {
    Api api = new Api().withCategory();
    as(api.admin).get("/categories").then().statusCode(200);
    given().get("/q/metrics").then().statusCode(200)
        .body(containsString("redis_commands_duration_seconds_count"))
        .body(containsString("redis_pool_queue_delay_seconds_count"))
        .body(containsString("redis_queue_delay_seconds_count"))
        .body(not(containsString("redis_queue_size")))
        .body(not(containsString("redis_current{")));
  }

  /** Lê a vitrine na falta e espera o retrato chegar ao Redis, porque a resposta não espera a gravação. */
  ValidatableResponse loadedRead(Api api, String id) {
    ValidatableResponse response = api.storefront(id).then().statusCode(200);
    awaitStored(CacheContents.storefront(api.tenant, id));
    return response;
  }

  void awaitStored(String key) {
    await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(10)).until(
        () -> redis.send(Request.cmd(Command.EXISTS).arg(key)).await().indefinitely().toInteger() == 1);
  }

  /** Lê a vitrine e confirma que a resposta veio do cache, pelo contador de acertos. */
  ValidatableResponse cachedRead(Api api, String id) {
    double hits = requests("storefront", "hit");
    ValidatableResponse response = api.storefront(id).then().statusCode(200);
    assertEquals(hits + 1, requests("storefront", "hit"), "a leitura vem do cache");
    return response;
  }

  double requests(String cache, String result) {
    return count(REQUESTS, cache, result);
  }

  double count(String name, String cache, String result) {
    Counter counter = registry.find(name).tags("cache", cache, "result", result).counter();
    return counter == null ? 0 : counter.count();
  }

  static String promotion(String salePrice, Instant startsAt, Instant endsAt) {
    return "{\"salePrice\":{\"amount\":" + salePrice + ",\"currency\":\"BRL\"},\"startsAt\":\"" + startsAt
        + "\",\"endsAt\":\"" + endsAt + "\"}";
  }
}
