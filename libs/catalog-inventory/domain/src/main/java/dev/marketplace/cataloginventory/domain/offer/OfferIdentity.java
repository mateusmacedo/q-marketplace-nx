package dev.marketplace.cataloginventory.domain.offer;

import java.util.UUID;

/**
 * O que identifica a oferta e não muda depois de criada: o SKU, o produto, o tenant e a loja a que ela pertence, e a
 * moeda, que é a do produto. As validações ficam com a {@link Offer}.
 */
public record OfferIdentity(UUID skuId, UUID productId, UUID tenantId, UUID storeId, String currency) {
}
