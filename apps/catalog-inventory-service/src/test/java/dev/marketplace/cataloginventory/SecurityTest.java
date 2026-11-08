package dev.marketplace.cataloginventory;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import static dev.marketplace.cataloginventory.Api.as;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class SecurityTest {
    @Test
    void requiresAuthentication() {
        given().get("/products").then().statusCode(401);
    }

    @Test
    void rejectsExpiredToken() {
        String token = TestTokens.claims(UUID.randomUUID(), "seller")
                .issuedAt(Instant.now().minusSeconds(3600)).expiresAt(Instant.now().minusSeconds(1800)).sign();
        as(token).get("/products").then().statusCode(401);
    }

    @Test
    void rejectsUnknownIssuer() {
        as(TestTokens.claims(UUID.randomUUID(), "seller").issuer("https://attacker.test").sign()).get("/products")
                .then().statusCode(401);
    }

    @Test
    void rejectsWrongAudience() {
        as(TestTokens.claims(UUID.randomUUID(), "seller").audience("other-service").sign()).get("/products").then()
                .statusCode(401);
    }

    @Test
    void rejectsRoleOutsideCatalog() {
        as(TestTokens.claims(UUID.randomUUID(), "buyer").sign()).get("/products").then().statusCode(403);
    }

    @Test
    void rejectsTokenWithoutTenant() {
        String token = Jwt.issuer(TestTokens.ISSUER).audience(TestTokens.AUDIENCE).subject("x").groups(Set.of("seller"))
                .expiresAt(Instant.now().plusSeconds(300)).sign();
        as(token).get("/products").then().statusCode(403).contentType("application/problem+json")
                .body("type", endsWith("/tenant-not-authorized"));
    }

    @Test
    void sellerWithoutStoreCannotCreateProduct() {
        Api api = new Api().withCategory();
        as(TestTokens.claims(api.tenant, "seller").sign()).body(api.productBody("Acme")).post("/products").then()
                .statusCode(403).body("type", endsWith("/access-denied"));
    }

    @Test
    void storeAndTenantHeadersAreIgnored() {
        Api api = new Api().withCategory();
        UUID otherStore = UUID.randomUUID();
        as(api.seller).header("X-Tenant-Id", UUID.randomUUID().toString()).header("X-Store-Id", otherStore.toString())
                .body(api.productBody("Acme")).post("/products").then().statusCode(201)
                .body("storeId", is(api.store.toString()));
    }

    @Test
    void moderationEndpointsRequireModerator() {
        Api api = new Api();
        as(api.seller).get("/moderation/products").then().statusCode(403);
        as(api.admin).get("/moderation/products").then().statusCode(403);
        as(api.moderator).get("/moderation/products").then().statusCode(200);
    }

    @Test
    void publicEndpointNeedsNoToken() {
        given().get("/storefront/" + UUID.randomUUID() + "/products/" + UUID.randomUUID()).then().statusCode(404)
                .contentType("application/problem+json");
    }
}
