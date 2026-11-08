package dev.marketplace.cataloginventory.cache;

import java.util.Optional;
import java.util.UUID;

/**
 * Chaves do cache no Redis, todas sob o prefixo {@code catalog:} (ADR 024). O segmento {@code v1} dos conteúdos muda
 * quando o formato do retrato muda, para versões diferentes do serviço não lerem o formato uma da outra.
 */
final class CacheKeys {
    static final String PREFIX = "catalog:";

    private CacheKeys() {
    }

    static String productGeneration(UUID tenantId, UUID productId) {
        return PREFIX + "gen:product:" + tenantId + ":" + productId;
    }

    /** Prefixo da geração da loja; o script de leitura completa com a loja lida do mapa produto→loja. */
    static String storeGenerationPrefix(UUID tenantId) {
        return PREFIX + "gen:store:" + tenantId + ":";
    }

    static String storeGeneration(UUID tenantId, UUID storeId) {
        return storeGenerationPrefix(tenantId) + storeId;
    }

    static String taxonomyGeneration(UUID tenantId) {
        return PREFIX + "gen:taxonomy:" + tenantId;
    }

    static String productStore(UUID tenantId, UUID productId) {
        return PREFIX + "product-store:" + tenantId + ":" + productId;
    }

    static String storefront(UUID tenantId, UUID productId) {
        return PREFIX + "storefront:v1:" + tenantId + ":" + productId;
    }

    static String chain(UUID tenantId, UUID categoryId) {
        return PREFIX + "taxonomy:v1:" + tenantId + ":chain:" + categoryId;
    }

    /** O cursor vai por último: é texto livre do chamador, e assim nenhum separador dele se confunde com os nossos. */
    static String children(UUID tenantId, Optional<UUID> parentId, int limit, Optional<String> afterCode) {
        return PREFIX + "taxonomy:v1:" + tenantId + ":children:" + parentId.map(UUID::toString).orElse("root") + ":"
                + limit + ":" + afterCode.orElse("");
    }
}
