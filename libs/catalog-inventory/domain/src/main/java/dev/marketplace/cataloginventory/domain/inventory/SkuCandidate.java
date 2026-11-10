package dev.marketplace.cataloginventory.domain.inventory;

import dev.marketplace.cataloginventory.domain.offer.Offer;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import java.util.UUID;

/**
 * Leitura autoritativa de um SKU para decidir se ele pode ser reservado;
 * {@code offer} só falta em produto não publicado.
 */
public record SkuCandidate(UUID productId, UUID storeId, ProductStatus productStatus, SellerStatus seller,
    Offer offer) {
}
