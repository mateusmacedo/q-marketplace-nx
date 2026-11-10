package dev.marketplace.cataloginventory.security;

import dev.marketplace.cataloginventory.config.CatalogConfig;
import dev.marketplace.cataloginventory.application.Actor;
import dev.marketplace.cataloginventory.application.Capability;
import jakarta.enterprise.context.RequestScoped;
import jakarta.json.JsonString;
import org.eclipse.microprofile.jwt.JsonWebToken;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Chamador derivado exclusivamente do token já validado. Tenant e loja nunca
 * vêm de cabeçalho ou corpo;
 * os papéis do token viram capacidades de negócio da aplicação.
 */
@RequestScoped
public class CallerContext {
  private final JsonWebToken token;
  private final CatalogConfig.Security config;

  CallerContext(JsonWebToken token, CatalogConfig config) {
    this.token = token;
    this.config = config.security();
  }

  /**
   * Monta o ator a partir do token já validado: tenant, loja (opcional), subject
   * e capacidades.
   * Falha com {@link TenantNotAuthorizedException} (403) se falta o claim de
   * tenant ou se o claim
   * de tenant ou de loja não é um UUID.
   */
  public Actor actor() {
    UUID tenant = uuidClaim(config.tenantClaim())
        .orElseThrow(() -> new TenantNotAuthorizedException("Token sem tenant associado"));
    String subject = token.getSubject() == null ? "desconhecido" : token.getSubject();
    return new Actor(tenant, uuidClaim(config.storeClaim()), subject, capabilities(token.getGroups()));
  }

  /**
   * Traduz os papéis do token em capacidades de negócio; papel desconhecido é
   * ignorado. {@code catalog-admin} concede taxonomia, catálogo de motivos de
   * moderação e leitura do catálogo e da elegibilidade do tenant, e
   * {@code catalog-moderator}, moderação e a elegibilidade, que o detalhe da
   * fila mostra.
   */
  static Set<Capability> capabilities(Set<String> roles) {
    Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
    if (roles == null)
      return capabilities;
    if (roles.contains(Roles.SELLER))
      capabilities.add(Capability.MANAGE_OWN_PRODUCTS);
    if (roles.contains(Roles.CATALOG_MODERATOR))
      capabilities.addAll(EnumSet.of(Capability.MODERATE_PRODUCTS, Capability.READ_SELLER_ELIGIBILITY));
    if (roles.contains(Roles.CATALOG_RESERVATIONS))
      capabilities.add(Capability.MANAGE_RESERVATIONS);
    if (roles.contains(Roles.CATALOG_ADMIN))
      capabilities.addAll(EnumSet.of(Capability.MANAGE_TAXONOMY, Capability.MANAGE_MODERATION_REASONS,
          Capability.READ_TENANT_CATALOG, Capability.READ_SELLER_ELIGIBILITY));
    return capabilities;
  }

  /**
   * Lê o claim como UUID; ausente ou em branco resulta em vazio e valor que não é
   * UUID falha com
   * {@link TenantNotAuthorizedException}.
   */
  private Optional<UUID> uuidClaim(String claim) {
    String raw = text(token.getClaim(claim));
    if (raw == null || raw.isBlank())
      return Optional.empty();
    try {
      return Optional.of(UUID.fromString(raw));
    } catch (IllegalArgumentException e) {
      throw new TenantNotAuthorizedException("Claim " + claim + " do token não é um UUID válido");
    }
  }

  /** Valor do claim como texto: a string JSON sem as aspas ou o {@code toString} do valor; nulo quando ausente. */
  private static String text(Object value) {
    if (value instanceof JsonString json)
      return json.getString();
    return value == null ? null : value.toString();
  }
}
