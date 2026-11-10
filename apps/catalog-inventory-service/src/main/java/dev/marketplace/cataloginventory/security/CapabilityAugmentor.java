package dev.marketplace.cataloginventory.security;

import dev.marketplace.cataloginventory.application.Capability;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dá à identidade autenticada as capacidades que o {@link CallerContext} deriva dos papéis do token, como permissões
 * que o {@code @PermissionsAllowed} dos endpoints confere. Assim o mapa de papel para capacidade fica só no
 * {@code CallerContext}, e o mesmo mapa responde o {@code GET /me}, protege a rota e decide no caso de uso.
 */
@ApplicationScoped
public class CapabilityAugmentor implements SecurityIdentityAugmentor {
  @Override
  public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
    if (identity.isAnonymous())
      return Uni.createFrom().item(identity);
    Set<String> capabilities = CallerContext.capabilities(identity.getRoles()).stream().map(Capability::name)
        .collect(Collectors.toSet());
    if (capabilities.isEmpty())
      return Uni.createFrom().item(identity);
    return Uni.createFrom()
        .item(QuarkusSecurityIdentity.builder(identity).addPermissionsAsString(capabilities).build());
  }
}
