package dev.marketplace.cataloginventory;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import java.util.function.Supplier;
import static dev.marketplace.cataloginventory.Api.as;
import static dev.marketplace.cataloginventory.Api.sku;
import static org.hamcrest.Matchers.*;

/**
 * Resposta das gravações de um produto quando a leitura do saldo, feita depois do commit, estoura o prazo: a
 * gravação vale e a resposta sai com o estado gravado, sem o {@code OUT_OF_STOCK} calculado e sem o saldo dos SKUs,
 * como no lote. Se outra leitura falhar, como a das ofertas, a resposta sai sem as ações e o que depende delas. A
 * leitura sem gravação continua respondendo 503.
 */
@QuarkusTest
class ProductWriteResponseTest {
    /** Faz a chamada com a leitura de saldo do tenant estourando o prazo. */
    static ValidatableResponse withStockTimeout(Api api, Supplier<ValidatableResponse> call) {
        StockReadTimeouts.failFor(api.tenant);
        try {
            return call.get();
        } finally {
            StockReadTimeouts.restore(api.tenant);
        }
    }

    @Test
    void writesAnswerWithTheRecordedStatusWhenTheStockReadTimesOut() {
        Api api = new Api().withCategory().approvedStore();
        String id = withStockTimeout(api, () -> as(api.seller).body(api.productBody("Acme", sku("ESG-P", "P", "10")))
                .post("/products").then().statusCode(201).header("ETag", is("\"0\""))
                .body("status", is("DRAFT")).body("skus[0].stock", nullValue())).extract().path("id");
        api.offer(api.skuId(id, "ESG-P"), "10");
        api.submit(id).then().statusCode(200).body("status", is("PENDING_MODERATION"));

        withStockTimeout(api, () -> api.approve(id).then().statusCode(200).header("ETag", is("\"2\""))
                .body("status", is("ACTIVE")).body("version", is(2)).body("skus[0].stock", nullValue())
                .body("skus[0].offer.salePrice.amount", is(10.0f)));
        withStockTimeout(api, () -> as(api.seller).get("/products/" + id).then().statusCode(503)
                .body("type", endsWith("/service-unavailable")));
        as(api.seller).get("/products/" + id).then().statusCode(200).body("status", is("OUT_OF_STOCK"))
                .body("skus[0].stock.available", is(0));

        api.post(api.seller, "/products/" + id + "/pause", null).then().statusCode(200)
                .body("status", is("PAUSED_BY_SELLER"));
        withStockTimeout(api, () -> api.post(api.seller, "/products/" + id + "/resume", null).then().statusCode(200)
                .header("ETag", is("\"4\"")).body("status", is("ACTIVE")).body("skus[0].stock", nullValue())
                .body("actions", hasItem("PAUSE")));
        as(api.seller).get("/products/" + id).then().statusCode(200).header("ETag", is("\"4\""))
                .body("status", is("OUT_OF_STOCK"));
    }

    @Test
    void writesAnswerWithoutActionsWhenAnotherReadTimesOut() {
        Api api = new Api().withCategory().approvedStore();
        String id = as(api.seller).body(api.productBody("Acme", sku("OFR-P", "P", "10"))).post("/products").then()
                .statusCode(201).extract().path("id");
        api.offer(api.skuId(id, "OFR-P"), "10");

        OfferReadTimeouts.failFor(api.tenant);
        try {
            api.submit(id).then().statusCode(200).header("ETag", is("\"1\""))
                    .body("status", is("PENDING_MODERATION")).body("version", is(1))
                    .body("actions", nullValue()).body("blockedActions", nullValue())
                    .body("saveReturnsToModeration", is(false)).body("publishingIssues", nullValue())
                    .body("skus[0].offer", nullValue());
            as(api.seller).get("/products/" + id).then().statusCode(503)
                    .body("type", endsWith("/service-unavailable"));
        } finally {
            OfferReadTimeouts.restore(api.tenant);
        }
        as(api.seller).get("/products/" + id).then().statusCode(200).header("ETag", is("\"1\""))
                .body("actions", notNullValue()).body("skus[0].offer.salePrice.amount", is(10.0f));
    }
}
