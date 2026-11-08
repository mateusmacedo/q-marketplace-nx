package dev.marketplace.cataloginventory;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static dev.marketplace.cataloginventory.Api.as;
import static dev.marketplace.cataloginventory.Api.putStock;
import static dev.marketplace.cataloginventory.Api.sku;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class InventoryResourceTest {
        @Test
        void approvedProductWithoutStockIsOutOfStockUntilReplenished() {
                Api api = new Api().withCategory().approvedStore();
                String id = api.createProduct(sku("CAM-P", "P", "50"));
                api.submit(id).then().statusCode(200);
                api.approve(id).then().statusCode(200).body("status", is("OUT_OF_STOCK"));
                api.storefront(id).then().statusCode(200).body("status", is("OUT_OF_STOCK")).body("skus[0].inStock",
                                is(false));
                api.stockAll(id, 3);
                as(api.seller).get("/products/" + id).then().body("status", is("ACTIVE"));
                as(api.seller).get("/inventory/skus/" + api.skuId(id, "CAM-P")).then().body("onHand", is(3)).body(
                                "available",
                                is(3));
                api.storefront(id).then().body("status", is("ACTIVE")).body("skus[0].inStock", is(true))
                                .body("skus[0]", not(hasKey("onHand")));
        }

        @Test
        void adjustmentUsesStockVersionAsETag() {
                Api api = new Api().withCategory();
                String skuId = api.skuId(api.createProduct(sku("CAM-P", "P", "50")), "CAM-P");
                String path = "/inventory/skus/" + skuId;
                as(api.seller).get(path).then().statusCode(200).header("ETag", is("\"0\"")).body("onHand", is(0)).body(
                                "skuId",
                                is(skuId));
                putStock(api.seller, skuId, 7, "\"0\"").then().statusCode(200).header("ETag", is("\"1\""))
                                .body("onHand", is(7)).body("available", is(7));
                putStock(api.seller, skuId, 8, "\"0\"").then().statusCode(412);
                putStock(api.seller, skuId, -1, null).then().statusCode(400);
                putStock(api.seller, skuId, 8, null).then().statusCode(200).body("version", is(2));
        }

        @Test
        void onlyTheOwnerAdjustsAndOthersMayRead() {
                Api api = new Api().withCategory();
                String skuId = api.skuId(api.createProduct(sku("CAM-P", "P", "50")), "CAM-P");
                putStock(api.otherStoreSeller(), skuId, 1, null).then().statusCode(404);
                putStock(api.moderator, skuId, 1, null).then().statusCode(403);
                as(api.moderator).get("/inventory/skus/" + skuId).then().statusCode(200);
                putStock(api.seller, UUID.randomUUID().toString(), 1, null).then().statusCode(404);
        }

        @Test
        void archivedProductFreezesItsStock() {
                Api api = new Api().withCategory();
                String id = api.createProduct(sku("CAM-P", "P", "50"));
                as(api.seller).delete("/products/" + id).then().statusCode(200);
                putStock(api.seller, api.skuId(id, "CAM-P"), 1, null).then().statusCode(409).body("type",
                                endsWith("/invalid-transition"));
        }

        @Test
        void stockCannotGoBelowReservedNorDisappearWithTheSku() {
                Api api = new Api().withCategory().approvedStore();
                String id = api.activeProduct(sku("CAM-P", "P", "50"));
                String skuId = api.skuId(id, "CAM-P");
                as(api.checkout).header("Idempotency-Key", UUID.randomUUID().toString())
                                .body("{\"items\":[{\"skuId\":\"" + skuId
                                                + "\",\"quantity\":4,\"expectedUnitPrice\":{\"amount\":50,\"currency\":\"BRL\"}}]}")
                                .post("/inventory/reservations").then().statusCode(201);
                putStock(api.seller, skuId, 3, null).then().statusCode(409).body("type",
                                endsWith("/stock-below-reserved"));
                putStock(api.seller, skuId, 4, null).then().statusCode(200).body("available", is(0));
                as(api.seller).get("/products/" + id).then().body("status", is("OUT_OF_STOCK"));

                String draft = api.createProduct(sku("BON-P", "P", "50"), sku("BON-M", "M", "50"));
                String stocked = api.skuId(draft, "BON-M");
                api.stock(stocked, 2);
                as(api.seller).delete("/products/" + draft + "/skus/" + stocked).then().statusCode(409).body("type",
                                endsWith("/sku-has-stock"));
                api.stock(stocked, 0);
                as(api.seller).delete("/products/" + draft + "/skus/" + stocked).then().statusCode(200).body("skus",
                                hasSize(1));
        }
}
