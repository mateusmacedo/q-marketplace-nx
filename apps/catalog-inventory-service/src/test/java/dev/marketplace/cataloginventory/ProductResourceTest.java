package dev.marketplace.cataloginventory;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static dev.marketplace.cataloginventory.Api.as;
import static dev.marketplace.cataloginventory.Api.idFrom;
import static dev.marketplace.cataloginventory.Api.sku;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ProductResourceTest {
        @Test
        void fullLifecycleFromDraftToStorefrontAndBlocked() {
                Api api = new Api().withCategory().approvedStore();
                String location = as(api.seller)
                                .body(api.productBody("Acme", sku("CAM-P", "P", "99.90"), sku("CAM-M", "M", "99.90")))
                                .post("/products").then().statusCode(201).header("ETag", is("\"0\""))
                                .body("status", is("DRAFT"))
                                .body("currency", is("BRL")).body("storeId", is(api.store.toString()))
                                .body("skus", hasSize(2))
                                .body("skus[0].dimensions.lengthCm", is(30.50f)).extract().header("Location");
                String id = idFrom(location);
                api.offer(api.skuId(id, "CAM-P"), "99.90");
                api.offer(api.skuId(id, "CAM-M"), "99.90");
                api.stockAll(id, 5);
                api.post(api.seller, "/products/" + id + "/submit", "\"0\"").then().statusCode(200)
                                .body("status", is("PENDING_MODERATION")).header("ETag", is("\"1\""));
                as(api.moderator).get("/moderation/products").then().statusCode(200).body("items.id", hasItem(id));
                api.post(api.moderator, "/moderation/products/" + id + "/approve", "\"1\"").then().statusCode(200)
                                .body("status", is("ACTIVE"));
                api.storefront(id).then().statusCode(200).header("Cache-Control", is("no-cache"))
                                .body("status", is("ACTIVE"))
                                .body("skus[0].price.salePrice.amount", is(99.90f))
                                .body("skus[0].price.listPrice.amount", is(120.00f))
                                .body("skus[0].inStock", is(true)).body("skus[0].dimensions.lengthCm", is(30.50f));
                as(api.moderator).body("{\"reason\":\"Produto proibido\"}")
                                .post("/moderation/products/" + id + "/block").then()
                                .statusCode(200).body("status", is("BLOCKED"));
                api.storefront(id).then().statusCode(404);
                as(api.seller).get("/products/" + id + "/history").then().statusCode(200)
                                .body("transition", contains("CREATE", "SUBMIT", "APPROVE", "BLOCK"))
                                .body("[3].reason", is("Produto proibido"));
        }

        @Test
        void submitListsEveryMissingRequirementIncludingOffers() {
                Api api = new Api().withCategory();
                String empty = as(api.seller).body(api.productBody(null)).post("/products").then().statusCode(201)
                                .extract()
                                .path("id");
                api.submit(empty).then().statusCode(422)
                                .contentType("application/problem+json")
                                .body("type", endsWith("/product-not-publishable"))
                                .body("reasons", contains("no-sku", "missing-attribute:marca"));
                String unpriced = api.draftProduct(sku("CAM-P", "P", "50"));
                api.submit(unpriced).then().statusCode(422)
                                .body("reasons", contains("missing-offer:CAM-P"));
        }

        @Test
        void approvalRequiresApprovedSellerAndKybRejectionHidesProduct() {
                Api api = new Api().withCategory();
                String id = api.createProduct(sku("CAM-P", "P", "50"));
                api.submit(id).then().statusCode(200);
                api.approve(id).then().statusCode(422).body("type", endsWith("/seller-not-eligible"));
                api.approvedStore();
                api.approve(id).then().statusCode(200);
                api.storefront(id).then().statusCode(200);
                api.kybVerdict("SellerRejected", "REJECTED");
                api.storefront(id).then().statusCode(404);
        }

        @Test
        void otherStoreAndOtherTenantGetNotFound() {
                Api api = new Api().withCategory();
                String id = api.createProduct(sku("CAM-P", "P", "50"));
                as(api.otherStoreSeller()).get("/products/" + id).then().statusCode(404);
                as(api.otherStoreSeller()).post("/products/" + id + "/pause").then().statusCode(404);
                as(api.otherTenantSeller()).get("/products/" + id).then().statusCode(404);
                as(api.admin).get("/products/" + id).then().statusCode(200);
                as(api.moderator).get("/products/" + id).then().statusCode(200);
        }

        @Test
        void contentRevisionRequiresIfMatchAndSendsBackToModeration() {
                Api api = new Api().withCategory().approvedStore();
                String id = api.activeProduct(sku("CAM-P", "P", "50"));
                String path = "/products/" + id;
                String revision = "{\"categoryId\":\"" + api.categoryId
                                + "\",\"title\":\"Camiseta premium\",\"attributes\":{\"marca\":\"Acme\"}}";
                as(api.seller).body(revision).put(path).then().statusCode(428);
                as(api.seller).header("If-Match", "\"0\"").body(revision).put(path).then().statusCode(412).body("type",
                                endsWith("/version-mismatch"));
                as(api.seller).header("If-Match", "W/\"2\"").body(revision).put(path).then().statusCode(400);
                as(api.seller).header("If-Match", api.etag(api.seller, path)).body(revision).put(path).then()
                                .statusCode(200)
                                .body("title", is("Camiseta premium")).body("status", is("PENDING_MODERATION"));
        }

        @Test
        void sellerSkuIsUniqueInTheStoreAtDatabaseLevel() {
                Api api = new Api().withCategory();
                api.createProduct(sku("CAM-P", "P", "50"));
                as(api.seller).body(api.productBody("Acme", sku("CAM-P", "M", "50"))).post("/products").then()
                                .statusCode(409)
                                .body("type", endsWith("/duplicate-seller-sku"));
                as(api.otherStoreSeller()).body(api.productBody("Acme", sku("CAM-P", "M", "50"))).post("/products")
                                .then().statusCode(201);
        }

        @Test
        void skuManagementFollowsProductState() {
                Api api = new Api().withCategory().approvedStore();
                String draft = api.createProduct(sku("CAM-P", "P", "50"), sku("CAM-M", "M", "50"));
                String removable = api.skuId(draft, "CAM-M");
                as(api.seller).delete("/products/" + draft + "/skus/" + removable).then().statusCode(200).body("skus",
                                hasSize(1));
                as(api.seller).get("/offers/" + removable).then().statusCode(404);
                String active = api.activeProduct(sku("BON-P", "P", "50"));
                String kept = api.skuId(active, "BON-P");
                as(api.seller).delete("/products/" + active + "/skus/" + kept).then().statusCode(422)
                                .body("type", endsWith("/sku-removal-requires-draft"));
                String skuLocation = as(api.seller).body(sku("BON-G", "G", "60").json())
                                .post("/products/" + active + "/skus")
                                .then()
                                .statusCode(201).body("status", is("PENDING_MODERATION")).body("skus", hasSize(2))
                                .extract()
                                .header("Location");
                as(api.seller).get(skuLocation).then().statusCode(200).body("sellerSku", is("BON-G"));
        }

        @Test
        void physicalSkuRequiresDimensionsAndInvalidAttributesAreReported() {
                Api api = new Api().withCategory();
                var noDimensions = new Api.SkuSpec("X1",
                                "{\"sellerSku\":\"X1\",\"variantAttributes\":{\"tamanho\":\"P\"},\"physical\":true}",
                                "10");
                as(api.seller).body(api.productBody("Acme", noDimensions)).post("/products").then().statusCode(422)
                                .body("type", endsWith("/missing-dimensions"));
                as(api.seller).body(api.productBody("Acme", sku("X2", "XG", "10"))).post("/products").then()
                                .statusCode(422)
                                .body("reasons", contains("invalid-attribute:X2:tamanho"));
        }

        @Test
        void currencyIsRequiredAndValidated() {
                Api api = new Api().withCategory();
                String body = api.productBody("Acme", sku("C1", "P", "10"));
                as(api.seller).body(body.replace("\"currency\":\"BRL\",", "")).post("/products").then().statusCode(400);
                as(api.seller).body(body.replace("\"BRL\"", "\"real\"")).post("/products").then().statusCode(400);
        }

        @Test
        void invalidTransitionsAndArchive() {
                Api api = new Api().withCategory().approvedStore();
                String id = api.createProduct(sku("CAM-P", "P", "50"));
                api.post(api.seller, "/products/" + id + "/pause", null).then().statusCode(409).body("type",
                                endsWith("/invalid-transition"));
                String active = api.activeProduct(sku("BON-P", "P", "50"));
                as(api.seller).delete("/products/" + active).then().statusCode(200).body("status", is("ARCHIVED"));
                api.storefront(active).then().statusCode(404);
                as(api.seller).get("/products/" + active).then().statusCode(200).body("status", is("ARCHIVED"));
        }

        @Test
        void productQueriesPaginateAndValidateParameters() {
                Api api = new Api().withCategory();
                api.createProduct(sku("A1", "P", "10"));
                api.createProduct(sku("A2", "P", "10"));
                as(api.otherStoreSeller()).body(api.productBody("Acme", sku("Z1", "P", "10"))).post("/products");
                String cursor = as(api.seller).queryParam("limit", 1).get("/products").then().statusCode(200)
                                .body("items", hasSize(1)).body("nextCursor", notNullValue()).extract()
                                .path("nextCursor");
                as(api.seller).queryParam("limit", 1).queryParam("cursor", cursor).get("/products").then()
                                .statusCode(200)
                                .body("items", hasSize(1)).body("nextCursor", nullValue());
                as(api.admin).queryParam("status", "DRAFT").get("/products").then().statusCode(200).body("items",
                                hasSize(3));
                as(api.admin).queryParam("storeId", api.store.toString()).get("/products").then().statusCode(200).body(
                                "items",
                                hasSize(2));
                as(api.seller).queryParam("status", "QUALQUER").get("/products").then().statusCode(400);
                as(api.seller).queryParam("status", "OUT_OF_STOCK").get("/products").then().statusCode(400)
                                .body("detail", containsString("derivado do saldo"));
                as(api.seller).queryParam("cursor", "bm8tdXVpZA").get("/products").then().statusCode(400);
                as(api.seller).queryParam("limit", 101).get("/products").then().statusCode(400);
        }

        @Test
        void countsCreatedProductsInMetrics() {
                Api api = new Api().withCategory();
                api.createProduct(sku("M1", "P", "10"));
                given().get("/q/metrics").then().statusCode(200).body(containsString("catalog_products_created_total"));
        }
}
