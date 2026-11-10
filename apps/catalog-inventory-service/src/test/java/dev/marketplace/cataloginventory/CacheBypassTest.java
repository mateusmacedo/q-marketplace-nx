package dev.marketplace.cataloginventory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static dev.marketplace.cataloginventory.Api.as;
import static dev.marketplace.cataloginventory.Api.sku;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Com o Redis fora do ar (ADR 024), a vitrine e a taxonomia respondem do banco e contam o desvio, e as escritas seguem
 * confirmadas, contando a invalidação perdida.
 */
@QuarkusTest
@TestProfile(CacheBypassTest.RedisDown.class)
class CacheBypassTest {
  public static class RedisDown implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // WHY: com hosts configurado, os Dev Services não sobem Redis; ninguém escuta na porta 1.
      return Map.of("quarkus.redis.hosts", "redis://127.0.0.1:1");
    }
  }

  @Inject
  MeterRegistry registry;

  @Test
  void storefrontAndTaxonomyAnswerFromTheDatabaseAndCountTheBypass() {
    Api api = new Api().withCategory().approvedStore();
    double failed = count("catalog.cache.invalidations", "storefront", "failed");
    String id = api.activeProduct(sku("BYP-P", "P", "50"));
    assertTrue(count("catalog.cache.invalidations", "storefront", "failed") > failed,
        "a escrita é confirmada e a invalidação perdida é contada");

    double bypass = count("catalog.cache.requests", "storefront", "bypass");
    api.storefront(id).then().statusCode(200).body("skus[0].price.salePrice.amount", is(50.00f));
    api.storefront(id).then().statusCode(200);
    assertEquals(bypass + 2, count("catalog.cache.requests", "storefront", "bypass"));

    double taxonomy = count("catalog.cache.requests", "taxonomy", "bypass");
    as(api.admin).get("/categories/" + api.categoryId).then().statusCode(200).body("code", is("camisetas"));
    assertEquals(taxonomy + 1, count("catalog.cache.requests", "taxonomy", "bypass"));
  }

  double count(String name, String cache, String result) {
    Counter counter = registry.find(name).tags("cache", cache, "result", result).counter();
    return counter == null ? 0 : counter.count();
  }
}
