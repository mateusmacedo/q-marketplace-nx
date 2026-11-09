package dev.marketplace.cataloginventory;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import static dev.marketplace.cataloginventory.Api.as;
import static dev.marketplace.cataloginventory.Api.idFrom;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CategoryResourceTest {
        @Test
        void createsTreeWithInheritedAttributes() {
                Api api = new Api();
                String location = as(api.admin).body("{\"code\":\"moda\",\"name\":\"Moda\",\"attributes\":[" +
                                "{\"code\":\"marca\",\"label\":\"Marca\",\"type\":\"TEXT\",\"required\":true,\"variant\":false}]}")
                                .post("/categories").then().statusCode(201).header("ETag", is("\"0\"")).extract()
                                .header("Location");
                String fashion = idFrom(location);
                as(api.admin).body("{\"parentId\":\"" + fashion
                                + "\",\"code\":\"camisetas\",\"name\":\"Camisetas\",\"attributes\":[" +
                                "{\"code\":\"tamanho\",\"label\":\"Tamanho\",\"type\":\"OPTION\",\"required\":true,\"variant\":true,"
                                +
                                "\"options\":[\"P\",\"M\"]}]}")
                                .post("/categories").then().statusCode(201)
                                .body("parentId", is(fashion)).body("usable", is(true))
                                .body("attributes.code", contains("tamanho"))
                                .body("effectiveAttributes.code", contains("marca", "tamanho"));
                as(api.seller).get(location).then().statusCode(200).body("code", is("moda"));
        }

        @Test
        void onlyAdminWritesAndCodeIsUnique() {
                Api api = new Api();
                as(api.seller).body(Api.CATEGORY).post("/categories").then().statusCode(403);
                as(api.admin).body(Api.CATEGORY).post("/categories").then().statusCode(201);
                as(api.admin).body(Api.CATEGORY).post("/categories").then().statusCode(409).body("type",
                                endsWith("/duplicate-category-code"));
                as(TestTokens.admin(UUID.randomUUID())).body(Api.CATEGORY).post("/categories").then().statusCode(201);
        }

        @Test
        void revisionUsesOptimisticConcurrency() {
                Api api = new Api().withCategory();
                String path = "/categories/" + api.categoryId;
                String body = "{\"name\":\"Camisetas e regatas\",\"active\":true,\"attributes\":[]}";
                as(api.admin).body(body).put(path).then().statusCode(428).body("type",
                                endsWith("/precondition-required"));
                as(api.admin).header("If-Match", "\"9\"").body(body).put(path).then().statusCode(412);
                as(api.admin).header("If-Match", "\"0\"").body(body).put(path).then().statusCode(200)
                                .header("ETag", is("\"1\"")).body("name", is("Camisetas e regatas"))
                                .body("version", is(1));
        }

        @Test
        void rejectsAttributeConflictWithReasons() {
                Api api = new Api().withCategory();
                as(api.admin).body("{\"parentId\":\"" + api.categoryId
                                + "\",\"code\":\"regatas\",\"name\":\"Regatas\",\"attributes\":[" +
                                "{\"code\":\"marca\",\"label\":\"Marca\",\"type\":\"TEXT\",\"required\":false,\"variant\":false}]}")
                                .post("/categories").then().statusCode(422)
                                .body("type", endsWith("/attribute-conflict"))
                                .body("reasons", contains("attribute-conflict:marca"));
        }

        @Test
        void validatesShapeBeforeDomain() {
                Api api = new Api();
                as(api.admin).body("{\"code\":\"\",\"name\":\"\"}").post("/categories").then().statusCode(400)
                                .body("errors.field", hasItems("code", "name"));
                as(api.admin).body("{\"code\":\"Moda\",\"name\":\"Moda\"}").post("/categories").then().statusCode(422)
                                .body("type", endsWith("/invalid-value"));
        }

        @Test
        void listsRootsWithCursor() {
                Api api = new Api();
                for (String code : new String[] { "c", "a", "b" }) {
                        as(api.admin).body("{\"code\":\"" + code + "\",\"name\":\"" + code + "\"}").post("/categories")
                                        .then()
                                        .statusCode(201);
                }
                String cursor = as(api.seller).queryParam("limit", 2).get("/categories").then().statusCode(200)
                                .body("items.code", contains("a", "b")).extract().path("nextCursor");
                as(api.seller).queryParam("cursor", cursor).get("/categories").then().statusCode(200)
                                .body("items.code", contains("c")).body("nextCursor", nullValue());
        }

        static String attribute(String code) {
                return "{\"code\":\"" + code + "\",\"label\":\"" + code
                                + "\",\"type\":\"TEXT\",\"required\":true,\"variant\":false}";
        }

        /** Cria a categoria (raiz quando {@code parentId} é nulo) com atributos de texto e devolve o id. */
        static String category(Api api, String parentId, String code, String... attributes) {
                String parent = parentId == null ? "" : "\"parentId\":\"" + parentId + "\",";
                return as(api.admin).body("{" + parent + "\"code\":\"" + code + "\",\"name\":\"" + code
                                + "\",\"attributes\":[" + String.join(",", Stream.of(attributes)
                                                .map(CategoryResourceTest::attribute).toList())
                                + "]}")
                                .post("/categories").then().statusCode(201).extract().path("id");
        }

        static Response move(String token, String id, String version, String parentId) {
                String parent = parentId == null ? "null" : "\"" + parentId + "\"";
                return as(token).header("If-Match", "\"" + version + "\"").body("{\"parentId\":" + parent + "}")
                                .put("/categories/" + id + "/parent");
        }

        @Test
        void movesSubtreeAndGrandchildInheritsTheNewPath() {
                Api api = new Api();
                String fashion = category(api, null, "moda", "marca");
                String tShirts = category(api, fashion, "camisetas", "tamanho");
                String tanks = category(api, tShirts, "regatas", "cor");
                String electronics = category(api, null, "eletronicos", "voltagem");
                move(api.admin, tShirts, "0", electronics).then().statusCode(200).header("ETag", is("\"1\""))
                                .body("parentId", is(electronics)).body("version", is(1))
                                .body("effectiveAttributes.code", contains("voltagem", "tamanho"));
                as(api.seller).get("/categories/" + tanks).then().statusCode(200).header("ETag", is("\"0\""))
                                .body("parentId", is(tShirts))
                                .body("effectiveAttributes.code", contains("voltagem", "tamanho", "cor"));
                as(api.seller).queryParam("parentId", electronics).get("/categories").then().statusCode(200)
                                .body("items.code", contains("camisetas"));
                as(api.seller).queryParam("parentId", fashion).get("/categories").then().statusCode(200)
                                .body("items", empty());
                move(api.admin, tShirts, "1", null).then().statusCode(200).body("parentId", nullValue())
                                .body("effectiveAttributes.code", contains("tamanho"));
                as(api.seller).get("/categories/" + tanks).then().statusCode(200)
                                .body("effectiveAttributes.code", contains("tamanho", "cor"));
        }

        @Test
        void moveRequiresIfMatchAdminExplicitParentAndExistingParent() {
                Api api = new Api();
                String fashion = category(api, null, "moda");
                String electronics = category(api, null, "eletronicos");
                String path = "/categories/" + fashion + "/parent";
                as(api.admin).body("{\"parentId\":\"" + electronics + "\"}").put(path).then().statusCode(428)
                                .body("type", endsWith("/precondition-required"));
                move(api.admin, fashion, "9", electronics).then().statusCode(412)
                                .body("type", endsWith("/version-mismatch"));
                move(api.seller, fashion, "0", electronics).then().statusCode(403);
                as(api.admin).header("If-Match", "\"0\"").body("{}").put(path).then().statusCode(400)
                                .body("type", endsWith("/malformed-body"));
                as(api.admin).body("{\"code\":\"x\",").post("/categories").then().statusCode(400)
                                .body("type", endsWith("/malformed-body"));
                move(api.admin, fashion, "0", UUID.randomUUID().toString()).then().statusCode(404);
                move(api.admin, fashion, "0", null).then().statusCode(200).header("ETag", is("\"0\""));
        }

        @Test
        void moveRefusesCycleAndAttributeConflict() {
                Api api = new Api();
                String fashion = category(api, null, "moda");
                String tShirts = category(api, fashion, "camisetas", "tamanho");
                category(api, tShirts, "regatas", "cor");
                move(api.admin, fashion, "0", tShirts).then().statusCode(422)
                                .body("type", endsWith("/category-cycle"));
                String paints = category(api, null, "tintas", "cor");
                move(api.admin, tShirts, "0", paints).then().statusCode(422)
                                .body("type", endsWith("/attribute-conflict"))
                                .body("reasons", contains("attribute-conflict:cor"));
        }

        @Test
        void oppositeConcurrentMovesNeverFormACycle() throws Exception {
                Api api = new Api();
                String left = category(api, null, "esquerda");
                String right = category(api, null, "direita");
                List<Integer> statuses = Api.race(2,
                                attempt -> attempt == 0 ? move(api.admin, left, "0", right)
                                                : move(api.admin, right, "0", left));
                assertEquals(1, statuses.stream().filter(status -> status == 200).count(), statuses::toString);
                assertTrue(statuses.stream().allMatch(status -> status == 200 || status == 409 || status == 422),
                                statuses::toString);
                String leftParent = as(api.seller).get("/categories/" + left).then().extract().path("parentId");
                String rightParent = as(api.seller).get("/categories/" + right).then().extract().path("parentId");
                assertTrue(leftParent == null || rightParent == null);
        }
}
