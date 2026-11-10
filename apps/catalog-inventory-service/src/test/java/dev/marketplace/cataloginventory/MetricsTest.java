package dev.marketplace.cataloginventory;

import dev.marketplace.cataloginventory.observability.SkuGauges;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static dev.marketplace.cataloginventory.Api.as;
import static dev.marketplace.cataloginventory.Api.sku;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

/** Métricas de negócio do PRD (ADR 017): moderação, SKUs ativos e vendáveis e latência de inventário. */
@QuarkusTest
class MetricsTest {
  @Inject
  MeterRegistry registry;
  @Inject
  SkuGauges skuGauges;

  @Test
  void moderationDecisionsCountApprovalsAndRejections() {
    Api api = new Api().withCategory().approvedStore();
    double approved = decisions("approved");
    double rejected = decisions("rejected");
    String toApprove = api.createProduct(sku("MOD-A", "P", "50"));
    api.submit(toApprove).then().statusCode(200);
    api.approve(toApprove).then().statusCode(200);
    String toReject = api.createProduct(sku("MOD-R", "M", "50"));
    api.submit(toReject).then().statusCode(200);
    as(api.moderator).body("{\"note\":\"Fotos fora do padrão\"}")
        .post("/moderation/products/" + toReject + "/reject").then().statusCode(200);
    assertEquals(approved + 1, decisions("approved"));
    assertEquals(rejected + 1, decisions("rejected"));
    given().get("/q/metrics").then().statusCode(200)
        .body(containsString("catalog_moderation_decisions_total{decision=\"approved\""))
        .body(containsString("catalog_moderation_decisions_total{decision=\"rejected\""));
  }

  /**
   * Os gauges contam o marketplace inteiro, então o teste compara a variação
   * entre disparos do agendador: rascunho com estoque não conta, e SKU sem
   * saldo ou de loja recusada continua ativo, mas deixa de ser vendável.
   */
  @Test
  void skuGaugesCountActiveAndSellableSkus() {
    Api api = new Api().withCategory().approvedStore();
    refreshSkuGauges();
    double active = gauge("catalog.skus.active");
    double sellable = gauge("catalog.skus.sellable");
    String id = api.activeProduct(sku("GAU-P", "P", "50"), sku("GAU-M", "M", "50"));
    api.stockAll(api.draftProduct(sku("GAU-G", "G", "50")), 5);
    refreshSkuGauges();
    assertEquals(active + 2, gauge("catalog.skus.active"));
    assertEquals(sellable + 2, gauge("catalog.skus.sellable"));
    api.stock(api.skuId(id, "GAU-M"), 0);
    refreshSkuGauges();
    assertEquals(active + 2, gauge("catalog.skus.active"));
    assertEquals(sellable + 1, gauge("catalog.skus.sellable"));
    api.kybVerdict("SellerRejected", "REJECTED");
    refreshSkuGauges();
    assertEquals(active + 2, gauge("catalog.skus.active"));
    assertEquals(sellable, gauge("catalog.skus.sellable"));
    given().get("/q/metrics").then().statusCode(200)
        .body(containsString("catalog_skus_active "))
        .body(containsString("catalog_skus_sellable "));
  }

  @Test
  void inventoryUpdateLatencyHasOwnHistogramAndHttpRequestsHaveNoBuckets() {
    Api api = new Api().withCategory();
    String id = api.createProduct(sku("LAT-P", "P", "50"));
    api.stock(api.skuId(id, "LAT-P"), 3);
    List<String> lines = given().get("/q/metrics").then().statusCode(200).extract().asString().lines().toList();
    assertTrue(lines.stream().anyMatch(line -> line.startsWith("catalog_inventory_update_latency_seconds_bucket{")
        && line.contains("operation=\"adjust_stock\"") && line.contains("outcome=\"success\"")),
        "histograma de adjust_stock");
    assertTrue(lines.stream().anyMatch(line -> line.startsWith("http_server_requests_seconds_count{")
        && line.contains("uri=\"/inventory/skus/{skuId}\"")), "requisições de PUT /inventory registradas");
    assertTrue(lines.stream().noneMatch(line -> line.startsWith("http_server_requests_seconds_bucket")),
        "http.server.requests sem buckets");
  }

  private double decisions(String decision) {
    Counter counter = registry.find("catalog.moderation.decisions").tag("decision", decision).counter();
    return counter == null ? 0 : counter.count();
  }

  private double gauge(String name) {
    return registry.get(name).gauge().value();
  }

  private void refreshSkuGauges() {
    skuGauges.refresh().await().atMost(Duration.ofSeconds(10));
  }
}
