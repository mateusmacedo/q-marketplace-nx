package dev.marketplace.cataloginventory.application;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Chamador autenticado: tenant, loja (quando seller) e capacidades, todos
 * derivados do token validado.
 */
public record Actor(UUID tenantId, Optional<UUID> storeId, String subject, Set<Capability> capabilities) {
    public Actor {
        capabilities = Set.copyOf(capabilities);
    }

    public boolean can(Capability capability) {
        return capabilities.contains(capability);
    }

    /**
     * Lê produtos de qualquer loja do tenant: administração do catálogo e
     * moderação.
     */
    public boolean canReadTenantCatalog() {
        return can(Capability.READ_TENANT_CATALOG) || can(Capability.MODERATE_PRODUCTS);
    }

    /** Indica se a loja do token é {@code store}; sem loja no token, é falso. */
    public boolean ownsStore(UUID store) {
        return storeId.map(store::equals).orElse(false);
    }

    /** Exige a capacidade; sem ela lança {@link AccessDeniedException}. */
    public void require(Capability capability) {
        if (!can(capability))
            throw new AccessDeniedException("Operação exige a permissão " + capability);
    }

    /**
     * Devolve a loja do token; lança {@link AccessDeniedException} quando o
     * token não tem loja (chamador que não é seller).
     */
    public UUID requireStore() {
        return storeId.orElseThrow(() -> new AccessDeniedException("Token sem loja associada"));
    }
}
