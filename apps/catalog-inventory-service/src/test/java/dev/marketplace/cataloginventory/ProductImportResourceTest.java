package dev.marketplace.cataloginventory;

import dev.marketplace.cataloginventory.imports.ProductCsv;
import dev.marketplace.cataloginventory.imports.ProductImportWorker;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import io.vertx.mutiny.sqlclient.Pool;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static dev.marketplace.cataloginventory.Api.as;
import static dev.marketplace.cataloginventory.Api.scalar;
import static io.restassured.RestAssured.given;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ProductImportResourceTest {
  static final String HEADER = String.join(",", ProductCsv.COLUMNS);
  @Inject
  ProductImportWorker worker;
  @Inject
  Pool pool;

  /** Linha de SKU físico da categoria de camisetas, com marca Acme. */
  static String line(String ref, String sellerSku, String size, String listPrice, String salePrice, int onHand) {
    return ref + ",camisetas,Camiseta básica,\"Algodão, gola careca\",marca=Acme," + sellerSku + ",tamanho="
        + size + ",true,3,25,30,200,BRL," + listPrice + "," + salePrice + "," + onHand;
  }

  static RequestSpecification csv(String token, String body) {
    return given().auth().oauth2(token).contentType("text/csv; charset=UTF-8").body(body.getBytes(UTF_8));
  }

  static String upload(String token, String body) {
    return csv(token, body).post("/products/imports").then().statusCode(202)
        .header("Location", containsString("/products/imports/")).body("status", is("PENDING"))
        .extract().header("Location");
  }

  /**
   * Dispara o processador até o lote concluir; ele atende um lote por vez, e
   * outros testes podem ter deixado lotes mais antigos na fila.
   */
  JsonPath processed(String token, String location) {
    await().atMost(Duration.ofSeconds(60)).until(() -> {
      worker.run().await().atMost(Duration.ofSeconds(30));
      return "COMPLETED".equals(as(token).get(location).then().statusCode(200).extract().path("status"));
    });
    return as(token).get(location).then().statusCode(200).extract().jsonPath();
  }

  long events(Api api, String eventType) {
    return scalar(pool, "SELECT count(*) AS n FROM outbox_event WHERE tenant_id = $1 AND event_type = $2", "n",
        Long.class, api.tenant, eventType);
  }

  @Test
  void sheetIsProcessedInTheBackgroundWithAResultPerLine() {
    Api api = new Api().withCategory();
    String existing = api.draftProduct(Api.sku("CAM-G", "G", "70"));
    String existingSku = api.skuId(existing, "CAM-G");
    api.offer(existingSku, "70");
    String location = upload(api.seller, HEADER + "\n"
        + line("basica", "IMP-P", "P", "79.90", "59.90", 5) + "\n"
        + line("basica", "IMP-M", "M", "", "59.90", 0) + "\n"
        + line("ignorada", "CAM-G", "P", "120.00", "65.00", 8).replace(",true,", ",talvez,") + "\n"
        + "outra,camisetas,Regata,,marca=Acme,REG-P,tamanho=P,true,3,25,30,200,BRL,,abc,1\n");

    JsonPath result = processed(api.seller, location);
    assertEquals(List.of("CREATED", "CREATED", "UPDATED", "FAILED"), result.getList("lines.outcome"));
    assertEquals(List.of(2, 3, 4, 5), result.getList("lines.lineNumber"));
    assertEquals("invalid-row", result.getString("lines[3].code"));
    assertEquals("invalid-row", result.getString("lines[3].reasonDetails[0].code"));
    assertEquals(result.getString("lines[3].message"), result.getString("lines[3].reasonDetails[0].message"));
    assertNull(result.get("lines[0].reasonDetails"));
    assertEquals(4, result.getInt("totalLines"));
    assertEquals(4, result.getInt("processedLines"));
    assertEquals(2, result.getInt("counts.created"));
    assertEquals(1, result.getInt("counts.updated"));
    assertEquals(1, result.getInt("counts.failed"));
    assertNotNull(result.getString("finishedAt"));
    assertEquals(existing, result.getString("lines[2].productId"));
    as(api.seller).queryParam("lines", false).get(location).then().statusCode(200)
        .body("status", is("COMPLETED")).body("totalLines", is(4)).body("processedLines", is(4))
        .body("counts.created", is(2)).body("counts.updated", is(1)).body("counts.unchanged", is(0))
        .body("counts.failed", is(1)).body("finishedAt", is(result.getString("finishedAt")))
        .body("$", hasKey("lines")).body("lines", nullValue());

    String imported = result.getString("lines[0].productId");
    assertEquals(imported, result.getString("lines[1].productId"));
    as(api.seller).get("/products/" + imported).then().statusCode(200).body("status", is("DRAFT"))
        .body("title", is("Camiseta básica")).body("description", is("Algodão, gola careca"))
        .body("skus.sellerSku", contains("IMP-P", "IMP-M"));
    String p = api.skuId(imported, "IMP-P");
    as(api.seller).get("/offers/" + p).then().statusCode(200).body("price.listPrice.amount", is(79.90f))
        .body("price.salePrice.amount", is(59.90f));
    as(api.seller).get("/inventory/skus/" + p).then().statusCode(200).body("onHand", is(5));
    as(api.seller).get("/offers/" + existingSku).then().statusCode(200).body("price.salePrice.amount", is(65.00f));
    as(api.seller).get("/inventory/skus/" + existingSku).then().statusCode(200).body("onHand", is(8));

    assertEquals(2, events(api, "ProductCreated"));
    assertEquals(4, events(api, "PriceChanged"));
    assertEquals(2, events(api, "InventoryUpdated"));
  }

  @Test
  void structurallyInvalidFileIsRejectedWithReasons() {
    Api api = new Api();
    csv(api.seller, "sku,price\nA,1\n").post("/products/imports").then().statusCode(400)
        .contentType(containsString("application/problem+json"))
        .body("type", endsWith("/invalid-import-file"))
        .body("reasons", hasItems("unknown-column:sku", "missing-column:product_ref"))
        .body("reasonDetails.find { it.code == 'unknown-column' }.params", contains("sku"))
        .body("reasonDetails.find { it.code == 'unknown-column' }.message",
            is("A coluna sku não faz parte do modelo."));
    csv(api.seller, HEADER + "\n").post("/products/imports").then().statusCode(400)
        .body("reasons", contains("no-rows"));
    given().auth().oauth2(api.seller).contentType("text/csv").body(new byte[]{'a', (byte) 0xC3, 0x28})
        .post("/products/imports").then().statusCode(400).body("reasons", contains("invalid-encoding"));
  }

  @Test
  void storeImportsAreListedNewestFirstWithCountsByCursor() {
    Api api = new Api().withCategory();
    String first = upload(api.seller, HEADER + "\n" + line("basica", "LST-P", "P", "", "59.90", 5) + "\n"
        + "outra,camisetas,Regata,,marca=Acme,REG-P,tamanho=P,true,3,25,30,200,BRL,,abc,1\n");
    processed(api.seller, first);
    String second = upload(api.seller, HEADER + "\n" + line("x", "LST-M", "M", "", "10", 1));
    String third = upload(api.seller, HEADER + "\n" + line("y", "LST-G", "G", "", "10", 1));
    upload(api.otherStoreSeller(), HEADER + "\n" + line("z", "LST-X", "P", "", "10", 1));

    JsonPath page = as(api.seller).queryParam("limit", 2).get("/products/imports").then().statusCode(200)
        .extract().jsonPath();
    assertEquals(List.of(Api.idFrom(third), Api.idFrom(second)), page.getList("items.id"));
    assertNull(page.get("items[0].lines"));
    String cursor = page.getString("nextCursor");
    JsonPath last = as(api.seller).queryParam("limit", 2).queryParam("cursor", cursor).get("/products/imports")
        .then().statusCode(200).body("nextCursor", nullValue()).extract().jsonPath();
    assertEquals(List.of(Api.idFrom(first)), last.getList("items.id"));
    assertEquals("COMPLETED", last.getString("items[0].status"));
    assertEquals(2, last.getInt("items[0].totalLines"));
    assertEquals(2, last.getInt("items[0].processedLines"));
    assertEquals(1, last.getInt("items[0].counts.created"));
    assertEquals(1, last.getInt("items[0].counts.failed"));
    assertNotNull(last.getString("items[0].finishedAt"));

    as(api.seller).queryParam("cursor", "!!").get("/products/imports").then().statusCode(400);
    as(api.seller).queryParam("limit", 0).get("/products/imports").then().statusCode(400);
    as(api.admin).get("/products/imports").then().statusCode(403);
  }

  @Test
  void onlyTheSubmittingStoreSeesTheImport() {
    Api api = new Api();
    String location = upload(api.seller, HEADER + "\n" + line("x", "X-1", "P", "", "10", 1));
    as(api.otherStoreSeller()).get(location).then().statusCode(404);
    as(api.otherStoreSeller()).queryParam("lines", false).get(location).then().statusCode(404);
    as(api.otherTenantSeller()).get(location).then().statusCode(404);
    as(api.admin).get(location).then().statusCode(403);
    as(api.seller).queryParam("lines", false).get(location).then().statusCode(200).body("status", is("PENDING"))
        .body("processedLines", is(0)).body("counts.created", is(0)).body("lines", nullValue());
    as(api.seller).queryParam("lines", true).get(location).then().statusCode(200).body("lines", empty());
    csv(api.admin, HEADER + "\n" + line("x", "X-1", "P", "", "10", 1)).post("/products/imports").then()
        .statusCode(403);
    as(api.seller).get(location).then().statusCode(200).body("totalLines", is(1));
  }
}
