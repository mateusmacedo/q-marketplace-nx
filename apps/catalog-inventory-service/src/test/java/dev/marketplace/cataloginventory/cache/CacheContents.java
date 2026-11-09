package dev.marketplace.cataloginventory.cache;

import dev.marketplace.cataloginventory.application.Page;
import java.util.Optional;
import java.util.UUID;

/** Chaves dos conteúdos do cache para os testes de fora do pacote, sem repetir o formato de {@link CacheKeys}. */
public final class CacheContents {
    private CacheContents() {
    }

    public static String storefront(UUID tenantId, String productId) {
        return CacheKeys.storefront(tenantId, UUID.fromString(productId));
    }

    public static String chain(UUID tenantId, String categoryId) {
        return CacheKeys.chain(tenantId, UUID.fromString(categoryId));
    }

    public static String moderationReasons(UUID tenantId) {
        return CacheKeys.moderationReasons(tenantId);
    }

    /** WHY: a listagem pede um item além da página para saber se há próxima, e esse limite entra na chave. */
    public static String rootPage(UUID tenantId) {
        return CacheKeys.children(tenantId, Optional.empty(), Page.DEFAULT_SIZE + 1, Optional.empty());
    }
}
