package dev.marketplace.cataloginventory;

import io.smallrye.jwt.build.Jwt;
import io.smallrye.jwt.build.JwtClaimsBuilder;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

final class TestTokens {
  static final String ISSUER = "https://issuer.test";
  static final String AUDIENCE = "catalog-inventory";

  private TestTokens() {
  }

  static JwtClaimsBuilder claims(UUID tenant, String... roles) {
    return Jwt.issuer(ISSUER).audience(AUDIENCE).subject("user-" + UUID.randomUUID())
        .claim("tenant_id", tenant.toString()).groups(Set.of(roles)).expiresAt(Instant.now().plusSeconds(300));
  }

  static String seller(UUID tenant, UUID store) {
    return claims(tenant, "seller").claim("store_id", store.toString()).sign();
  }

  static String admin(UUID tenant) {
    return claims(tenant, "catalog-admin").sign();
  }

  static String moderator(UUID tenant) {
    return claims(tenant, "catalog-moderator").sign();
  }

  static String reservations(UUID tenant) {
    return claims(tenant, "catalog-reservations").sign();
  }
}
