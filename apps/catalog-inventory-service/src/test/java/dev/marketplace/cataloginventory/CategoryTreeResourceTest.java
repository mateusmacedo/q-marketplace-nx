package dev.marketplace.cataloginventory;

import dev.marketplace.cataloginventory.application.CategoryService;
import dev.marketplace.cataloginventory.application.Page;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import io.vertx.mutiny.sqlclient.Pool;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static dev.marketplace.cataloginventory.Api.as;
import static dev.marketplace.cataloginventory.Api.scalar;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CategoryTreeResourceTest {
    @Inject
    Pool pool;

    static String category(Api api, String parentId, String code, String attributes) {
        String parent = parentId == null ? "" : "\"parentId\":\"" + parentId + "\",";
        return as(api.admin).body("{" + parent + "\"code\":\"" + code + "\",\"name\":\"Nome " + code
                + "\",\"attributes\":[" + attributes + "]}").post("/categories").then().statusCode(201)
                .extract()
                .path("id");
    }

    static String text(String code) {
        return "{\"code\":\"" + code + "\",\"label\":\"" + code + "\",\"type\":\"TEXT\",\"required\":false,"
                + "\"variant\":false}";
    }

    @Test
    void responsesCarryPathLevelChildCountAndAttributeOrigin() {
        Api api = new Api();
        String fashion = category(api, null, "moda", text("material"));
        String shirts = category(api, fashion, "camisas", text("gola"));
        category(api, shirts, "polos", "");
        category(api, shirts, "sociais", "");
        as(api.seller).get("/categories/" + shirts).then().statusCode(200)
                .body("ancestors.code", contains("moda")).body("ancestors.name", contains("Nome moda"))
                .body("level", is(2)).body("childCount", is(2))
                .body("effectiveAttributes.code", contains("material", "gola"))
                .body("effectiveAttributes.categoryId", contains(fashion, shirts));
        as(api.admin).queryParam("parentId", fashion).get("/categories").then().statusCode(200)
                .body("items[0].code", is("camisas")).body("items[0].ancestors.id", contains(fashion))
                .body("items[0].level", is(2)).body("items[0].childCount", is(2))
                .body("items[0].attributeCount", is(1)).body("items[0].usable", is(true));
        as(api.admin).get("/categories").then().statusCode(200).body("items[0].childCount", is(1))
                .body("items[0].ancestors", empty()).body("items[0].level", is(1));
    }

    @Test
    void treeListsTheWholeTenantInTreeOrderByCursor() {
        Api api = new Api();
        String a = category(api, null, "a", "");
        category(api, null, "a-b", "");
        String x = category(api, a, "x", "");
        category(api, x, "y", "");
        JsonPath first = as(api.seller).queryParam("limit", 2).get("/categories/tree").then().statusCode(200)
                .extract().jsonPath();
        assertEquals(List.of("a", "x"), first.getList("items.code"));
        JsonPath second = as(api.seller).queryParam("limit", 2)
                .queryParam("cursor", first.getString("nextCursor"))
                .get("/categories/tree").then().statusCode(200).body("nextCursor", nullValue())
                .extract().jsonPath();
        assertEquals(List.of("y", "a-b"), second.getList("items.code"));
        assertEquals(List.of("a", "x"), second.getList("items[0].ancestors.code"));
        assertEquals(3, second.getInt("items[0].level"));
        assertEquals(1, first.getInt("items[1].childCount"));

        String etag = api.etag(api.admin, "/categories/" + a);
        as(api.admin).header("If-Match", etag).body("{\"name\":\"A\",\"active\":false,\"attributes\":[]}")
                .put("/categories/" + a).then().statusCode(200);
        as(api.seller).queryParam("usable", true).get("/categories/tree").then().statusCode(200)
                .body("items.code", contains("a-b"));
        as(api.seller).queryParam("cursor", "QSBC").get("/categories/tree").then().statusCode(400);
        as(TestTokens.admin(java.util.UUID.randomUUID())).get("/categories/tree").then().statusCode(200)
                .body("items", empty());
    }

    /**
     * Lê a árvore inteira em páginas de até {@code limit} itens e devolve os
     * códigos na ordem lida.
     */
    static List<String> pagedTree(String token, int limit, boolean usable) {
        List<String> codes = new ArrayList<>();
        String cursor = null;
        do {
            RequestSpecification request = as(token).queryParam("limit", limit).queryParam("usable",
                    usable);
            if (cursor != null)
                request = request.queryParam("cursor", cursor);
            JsonPath page = request.get("/categories/tree").then().statusCode(200).extract().jsonPath();
            List<String> items = page.getList("items.code");
            assertTrue(items.size() <= limit, "página com " + items.size() + " itens");
            codes.addAll(items);
            cursor = page.getString("nextCursor");
        } while (cursor != null);
        return codes;
    }

    @Test
    void treePagesOfAnySizeReadTheSameTreeAsASinglePage() {
        Api api = new Api();
        String a = category(api, null, "a", "");
        String x = category(api, a, "x", "");
        category(api, category(api, x, "z", ""), "w", "");
        category(api, category(api, a, "x-y", ""), "y", "");
        category(api, category(api, null, "a-b", ""), "c", "");
        category(api, null, "b", "");
        List<String> tree = List.of("a", "x", "z", "w", "x-y", "y", "a-b", "c", "b");
        assertEquals(tree, as(api.seller).get("/categories/tree").then().statusCode(200)
                .body("nextCursor", nullValue()).extract().jsonPath().getList("items.code"));
        for (int limit = 1; limit <= 4; limit++)
            assertEquals(tree, pagedTree(api.seller, limit, false), "limit " + limit);

        String etag = api.etag(api.admin, "/categories/" + x);
        as(api.admin).header("If-Match", etag).body("{\"name\":\"X\",\"active\":false,\"attributes\":[]}")
                .put("/categories/" + x).then().statusCode(200);
        List<String> usable = List.of("a", "x-y", "y", "a-b", "c", "b");
        assertEquals(usable, pagedTree(api.seller, CategoryService.TREE_PAGE_SIZE, true));
        assertEquals(usable, pagedTree(api.seller, 2, true));

        as(api.seller).queryParam("limit", CategoryService.TREE_PAGE_SIZE + 1).get("/categories/tree").then()
                .statusCode(400);
        as(api.seller).queryParam("limit", Page.MAX_SIZE + 1).get("/categories").then().statusCode(400);
    }

    @Test
    void idsReadOnlyTheRequestedCategoriesOfTheTenant() {
        Api api = new Api();
        String fashion = category(api, null, "moda", "");
        String shirts = category(api, fashion, "camisas", text("gola"));
        category(api, shirts, "polos", "");
        String home = category(api, null, "casa", "");
        String foreign = category(new Api(), null, "moda", "");
        as(api.seller).queryParam("ids", shirts + "," + home, foreign, UUID.randomUUID().toString(), shirts)
                .get("/categories").then().statusCode(200)
                .body("items.id", contains(shirts, home)).body("nextCursor", nullValue())
                .body("items[0].ancestors.code", contains("moda")).body("items[0].level", is(2))
                .body("items[0].childCount", is(1)).body("items[0].attributeCount", is(1))
                .body("items[1].ancestors", empty());

        List<String> hundred = new ArrayList<>(List.of(home));
        while (hundred.size() < CategoryService.MAX_IDS)
            hundred.add(UUID.randomUUID().toString());
        as(api.seller).queryParam("ids", String.join(",", hundred)).get("/categories").then().statusCode(200)
                .body("items.id", contains(home));
        hundred.add(UUID.randomUUID().toString());
        as(api.seller).queryParam("ids", String.join(",", hundred)).get("/categories").then().statusCode(400);
        as(api.seller).queryParam("ids", home).queryParam("parentId", fashion).get("/categories").then()
                .statusCode(400);
        as(api.seller).queryParam("ids", home).queryParam("cursor", Base64.getUrlEncoder().withoutPadding()
                .encodeToString("casa".getBytes(StandardCharsets.UTF_8))).get("/categories").then()
                .statusCode(400);
        as(api.seller).queryParam("ids", home + ",x").get("/categories").then().statusCode(400)
                .body("type", endsWith("/invalid-request"));
    }

    @Test
    void deleteRemovesOnlyTheEmptyCategory() {
        Api api = new Api().withCategory();
        String tanks = category(api, api.categoryId, "regatas", "");
        String path = "/categories/" + api.categoryId;
        as(api.admin).delete(path).then().statusCode(428);
        as(api.admin).header("If-Match", "\"0\"").delete(path).then().statusCode(409)
                .body("type", endsWith("/category-has-children"))
                .body("reasons", contains("category-has-children"));
        as(api.seller).header("If-Match", "\"0\"").delete("/categories/" + tanks).then().statusCode(403);
        as(api.admin).header("If-Match", "\"3\"").delete("/categories/" + tanks).then().statusCode(412);
        as(api.admin).header("If-Match", "\"0\"").delete("/categories/" + tanks).then().statusCode(204);
        as(api.admin).get("/categories/" + tanks).then().statusCode(404);
        as(api.admin).get(path).then().statusCode(200).body("childCount", is(0));

        String product = api.draftProduct(Api.sku("CAM-P", "P", "50"));
        as(api.seller).delete("/products/" + product).then().statusCode(200).body("status", is("ARCHIVED"));
        as(api.admin).header("If-Match", "\"0\"").delete(path).then().statusCode(409)
                .body("type", endsWith("/category-has-products"))
                .body("reasons", contains("category-has-products"));
    }

    @Test
    void foreignKeysKeepDeletionAndConcurrentProductConsistent() throws Exception {
        Api api = new Api().withCategory();
        List<Integer> statuses = Api.race(2, attempt -> attempt == 0
                ? as(api.admin).header("If-Match", "\"0\"").delete("/categories/" + api.categoryId)
                : as(api.seller).body(api.productBody("Acme", Api.sku("RACE-P", "P", "50")))
                        .post("/products"));
        long products = scalar(pool, "SELECT count(*) AS n FROM product WHERE tenant_id = $1", "n", Long.class,
                api.tenant);
        long categories = scalar(pool, "SELECT count(*) AS n FROM category WHERE tenant_id = $1", "n",
                Long.class,
                api.tenant);
        if (statuses.get(0) == 204) {
            assertTrue(Set.of(404, 409).contains(statuses.get(1)), "produto: " + statuses);
            assertEquals(0, products);
            assertEquals(0, categories);
        } else {
            assertEquals(List.of(409, 201), statuses);
            assertEquals(1, products);
            assertEquals(1, categories);
        }
    }

    @Test
    void batchActivatesDeactivatesAndDeletesBranchesFromTheBottom() {
        Api api = new Api();
        String fashion = category(api, null, "moda", "");
        String shirts = category(api, fashion, "camisas", "");
        String polos = category(api, shirts, "polos", "");
        String home = category(api, null, "casa", "");

        as(api.admin).body("{\"action\":\"DEACTIVATE\",\"items\":[{\"id\":\"" + fashion + "\",\"version\":0},"
                + "{\"id\":\"" + home + "\",\"version\":5},{\"id\":\"" + polos + "\"}]}")
                .post("/categories/batch").then().statusCode(200)
                .body("results.id", contains(fashion, home, polos))
                .body("results.outcome", contains("SUCCEEDED", "FAILED", "FAILED"))
                .body("results[0].active", is(false)).body("results[0].version", is(1))
                .body("results[1].problem.status", is(412))
                .body("results[2].problem.type", endsWith("/precondition-required"));
        as(api.admin).get("/categories/" + polos).then().body("usable", is(false));

        as(api.admin).body("{\"action\":\"DELETE\",\"items\":[{\"id\":\"" + fashion + "\",\"version\":1},"
                + "{\"id\":\"" + polos + "\",\"version\":0},{\"id\":\"" + shirts
                + "\",\"version\":0}]}")
                .post("/categories/batch").then().statusCode(200)
                .body("results.id", contains(fashion, polos, shirts))
                .body("results.outcome", everyItem(is("SUCCEEDED")))
                .body("results[0].version", nullValue());
        as(api.admin).get("/categories/tree").then().body("items.code", contains("casa"));

        as(api.admin).body("{\"action\":\"ACTIVATE\",\"items\":[{\"id\":\"" + home + "\",\"version\":0},"
                + "{\"id\":\"" + home + "\",\"version\":0}]}").post("/categories/batch").then()
                .statusCode(400);
        as(api.admin).body("{\"action\":\"ACTIVATE\",\"items\":[]}").post("/categories/batch").then()
                .statusCode(400);
        as(api.seller).body("{\"action\":\"ACTIVATE\",\"items\":[{\"id\":\"" + home + "\",\"version\":0}]}")
                .post("/categories/batch").then().statusCode(403);
        as(api.admin).body("{\"action\":\"ACTIVATE\",\"items\":[{\"id\":\"" + home + "\",\"version\":0}]}")
                .post("/categories/batch").then().statusCode(200).body("results[0].active", is(true))
                .body("results[0].version", is(0));
    }
}
