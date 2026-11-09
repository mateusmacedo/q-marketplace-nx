package dev.marketplace.cataloginventory;

import dev.marketplace.cataloginventory.domain.category.Category;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static dev.marketplace.cataloginventory.Api.as;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/** O corpo de erro traz, ao lado de {@code reasons}, cada motivo com parâmetros e o texto para mostrar. */
@QuarkusTest
class ReasonDetailsTest {
    @Test
    void refusedPublicationExplainsEachReasonWithTheAttributeLabel() {
        Api api = new Api().withCategory();
        String empty = as(api.seller).body(api.productBody(null)).post("/products").then().statusCode(201).extract()
                .path("id");
        api.submit(empty).then().statusCode(422).body("type", endsWith("/product-not-publishable"))
                .body("reasons", contains("no-sku", "missing-attribute:marca"))
                .body("reasonDetails.code", contains("no-sku", "missing-attribute"))
                .body("reasonDetails[0].params", empty())
                .body("reasonDetails[1].params", contains("marca"))
                .body("reasonDetails.message", contains("Adicione ao menos um SKU.", "Preencha o atributo Marca."));
    }

    @Test
    void refusalWithoutReasonsCarriesItsOwnCodeAndTheLimitInForce() {
        Api api = new Api();
        String parent = null;
        for (int level = 0; level < Category.MAX_DEPTH; level++)
            parent = CategoryTreeResourceTest.category(api, parent, "n" + level, "");
        String deepest = parent;
        as(api.admin).body("{\"parentId\":\"" + deepest + "\",\"code\":\"fundo\",\"name\":\"Fundo\",\"attributes\":[]}")
                .post("/categories").then().statusCode(422).body("type", endsWith("/category-too-deep"))
                .body("reasons", nullValue())
                .body("reasonDetails.code", contains("category-too-deep"))
                .body("reasonDetails[0].params", contains(String.valueOf(Category.MAX_DEPTH)))
                .body("reasonDetails[0].message", is("A árvore de categorias aceita até " + Category.MAX_DEPTH
                        + " níveis e passaria disso nesse lugar."));
    }

    @Test
    void conflictKeepsTheReasonsAndAddsTheirText() {
        Api api = new Api().withCategory();
        CategoryTreeResourceTest.category(api, api.categoryId, "regatas", "");
        as(api.admin).header("If-Match", "\"0\"").delete("/categories/" + api.categoryId).then().statusCode(409)
                .body("reasons", contains("category-has-children"))
                .body("reasonDetails[0].code", is("category-has-children"))
                .body("reasonDetails[0].message", is("Exclua ou mova as subcategorias antes de excluir esta categoria."));
    }

    @Test
    void problemsOutsideBusinessRulesHaveNoReasonDetails() {
        Api api = new Api();
        as(api.seller).get("/products/" + java.util.UUID.randomUUID()).then().statusCode(404)
                .body("reasonDetails", nullValue());
    }
}
