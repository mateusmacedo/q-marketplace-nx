package dev.marketplace.cataloginventory;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import static dev.marketplace.cataloginventory.Api.as;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class SessionResourceTest {
    final UUID tenant = UUID.randomUUID();

    @Test
    void sellerSeesOwnStoreAndManagesOwnProducts() {
        UUID store = UUID.randomUUID();
        as(TestTokens.claims(tenant, "seller").subject("user-42").claim("store_id", store.toString()).sign())
                .get("/me").then().statusCode(200)
                .body("subject", is("user-42")).body("tenantId", is(tenant.toString()))
                .body("storeId", is(store.toString()))
                .body("capabilities", contains("MANAGE_OWN_PRODUCTS"))
                .body("can.readTenantCatalog", is(false));
    }

    @Test
    void moderatorModeratesAndReadsTheTenantCatalog() {
        as(TestTokens.moderator(tenant)).get("/me").then().statusCode(200).body("storeId", nullValue())
                .body("capabilities", contains("MODERATE_PRODUCTS", "READ_SELLER_ELIGIBILITY"))
                .body("can.readTenantCatalog", is(true));
    }

    @Test
    void adminManagesTaxonomyAndReadsTheTenantCatalog() {
        as(TestTokens.admin(tenant)).get("/me").then().statusCode(200)
                .body("capabilities", contains("MANAGE_TAXONOMY", "READ_TENANT_CATALOG", "READ_SELLER_ELIGIBILITY",
                        "MANAGE_MODERATION_REASONS"))
                .body("can.readTenantCatalog", is(true));
    }

    @Test
    void reservationAccountOnlyReserves() {
        as(TestTokens.reservations(tenant)).get("/me").then().statusCode(200)
                .body("capabilities", contains("MANAGE_RESERVATIONS")).body("can.readTenantCatalog", is(false));
    }

    @Test
    void roleOutsideTheCatalogHasNoCapabilities() {
        as(TestTokens.claims(tenant, "buyer").sign()).get("/me").then().statusCode(200)
                .body("tenantId", is(tenant.toString())).body("capabilities", empty())
                .body("can.readTenantCatalog", is(false));
        as(TestTokens.claims(tenant).sign()).get("/me").then().statusCode(200).body("capabilities", empty());
    }

    @Test
    void requiresATokenWithTenant() {
        given().get("/me").then().statusCode(401);
        String withoutTenant = Jwt.issuer(TestTokens.ISSUER).audience(TestTokens.AUDIENCE).subject("x")
                .groups(Set.of("seller")).expiresAt(Instant.now().plusSeconds(300)).sign();
        as(withoutTenant).get("/me").then().statusCode(403).body("type", endsWith("/tenant-not-authorized"));
    }
}
