package dev.marketplace.cataloginventory;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static dev.marketplace.cataloginventory.Api.as;
import static dev.marketplace.cataloginventory.Api.sku;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Caractere nulo (U+0000) em texto do corpo JSON, que o PostgreSQL não guarda: toda escrita responde 400
 * {@code validation-failed} com o caminho do campo, como a validação de campo, sem gravar nada.
 */
@QuarkusTest
class NullCharacterResourceTest {
    /** Escape JSON do caractere nulo, como o cliente o envia. */
    static final String NULL = "\\u0000";

    /** Confere a recusa com um único campo e sem o caractere nulo no corpo do problema. */
    static void refused(Response response, String field) {
        String body = response.then().statusCode(400).contentType("application/problem+json")
                .body("type", endsWith("/validation-failed"))
                .body("errors.field", contains(field))
                .body("errors.message", contains(containsString("U+0000")))
                .extract().asString();
        assertFalse(body.contains(NULL) || body.indexOf('\u0000') >= 0, body);
    }

    @Test
    void productWritesRefuseTheNullCharacterInTextValuesAndKeys() {
        Api api = new Api().withCategory();
        String body = api.productBody("Acme", sku("CAM-P", "P", "10"));
        refused(as(api.seller).body(body.replace("Camiseta básica", "Camiseta" + NULL)).post("/products"), "title");
        refused(as(api.seller).body(body.replace("\"Acme\"", "\"Ac" + NULL + "me\"")).post("/products"),
                "attributes[marca]");
        refused(as(api.seller).body(body.replace("\"marca\"", "\"mar" + NULL + "ca\"")).post("/products"),
                "attributes");
        refused(as(api.seller).body(body.replace("\"P\"", "\"P" + NULL + "\"")).post("/products"),
                "skus[0].variantAttributes[tamanho]");
        as(api.seller).get("/products").then().statusCode(200).body("items", empty());

        String id = api.draftProduct(sku("CAM-P", "P", "10"));
        String etag = api.etag(api.seller, "/products/" + id);
        String revision = """
                {"categoryId":"%s","title":"Camiseta","description":"Algodão%s","attributes":{"marca":"Acme"}}"""
                .formatted(api.categoryId, NULL);
        refused(as(api.seller).header("If-Match", etag).body(revision).put("/products/" + id), "description");
        as(api.seller).get("/products/" + id).then().statusCode(200).header("ETag", is(etag))
                .body("description", is("Algodão"));
    }

    @Test
    void categoryWritesRefuseTheNullCharacterInNamesLabelsAndOptions() {
        Api api = new Api();
        refused(as(api.admin).body(Api.CATEGORY.replace("\"Camisetas\"", "\"Camisetas" + NULL + "\""))
                .post("/categories"), "name");
        refused(as(api.admin).body(Api.CATEGORY.replace("\"Marca\"", "\"Mar" + NULL + "ca\"")).post("/categories"),
                "attributes[0].label");
        refused(as(api.admin).body(Api.CATEGORY.replace("\"G\"", "\"G" + NULL + "\"")).post("/categories"),
                "attributes[1].options[2]");
        as(api.admin).get("/categories").then().statusCode(200).body("items", empty());
    }

    @Test
    void moderationWritesRefuseTheNullCharacterInLabelsCodesAndNotes() {
        Api api = new Api().withCategory().withModerationReasons();
        refused(as(api.admin).body("{\"code\":\"spam\",\"label\":\"Spam" + NULL + "\",\"decisions\":[\"REJECT\"]}")
                .post("/moderation/reasons"), "label");
        as(api.admin).get("/moderation/reasons/spam").then().statusCode(404);

        String id = api.createProduct(sku("CAM-P", "P", "10"));
        api.submit(id).then().statusCode(200);
        String reject = "/moderation/products/" + id + "/reject";
        refused(as(api.moderator).body("{\"reasonCodes\":[\"wrong-category\"],\"note\":\"Rever" + NULL + "\"}")
                .post(reject), "note");
        refused(as(api.moderator).body("{\"reasonCodes\":[\"wrong-category\",\"price" + NULL + "\"]}").post(reject),
                "reasonCodes[1]");
        as(api.seller).get("/products/" + id).then().statusCode(200).body("status", is("PENDING_MODERATION"));
    }

    @Test
    void reservationRefusesTheNullCharacterInTheOrderReference() {
        Api api = new Api();
        String body = """
                {"orderRef":"pedido%s","items":[{"skuId":"%s","quantity":1,
                 "expectedUnitPrice":{"amount":10.00,"currency":"BRL"}}]}""".formatted(NULL, UUID.randomUUID());
        refused(as(api.checkout).header("Idempotency-Key", UUID.randomUUID().toString()).body(body)
                .post("/inventory/reservations"), "orderRef");
    }
}
