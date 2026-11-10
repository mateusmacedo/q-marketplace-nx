package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.api.OfferModels.EffectivePriceDto;
import dev.marketplace.cataloginventory.api.ProductModels.DimensionsDto;
import dev.marketplace.cataloginventory.application.StorefrontService.StorefrontProduct;
import dev.marketplace.cataloginventory.domain.inventory.StockLevel;
import dev.marketplace.cataloginventory.domain.offer.Offer;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import dev.marketplace.cataloginventory.domain.product.Sku;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contrato HTTP da vitrine: conteúdo do produto, preço vigente de cada SKU e
 * disponibilidade, sem quantidades.
 */
public final class StorefrontModels {
  private StorefrontModels() {
  }

  public record StorefrontSkuResponse(UUID id, String sellerSku, Map<String, String> variantAttributes,
      boolean physical,
      DimensionsDto dimensions,
      EffectivePriceDto price,
      @Schema(description = "Há saldo disponível; a quantidade exata não é exposta") boolean inStock) {
    /**
     * INVARIANT: produto visível tem oferta e saldo em cada SKU; a publicação e a
     * criação do SKU garantem.
     */
    static StorefrontSkuResponse of(Sku s, Offer offer, StockLevel level, Instant now) {
      return new StorefrontSkuResponse(s.id(), s.sellerSku(), s.variantAttributes(), s.physical(),
          DimensionsDto.of(s.dimensions().orElse(null)), EffectivePriceDto.of(offer.effectivePrice(now)),
          level.available() > 0);
    }
  }

  public record StorefrontProductResponse(UUID id, UUID storeId, UUID categoryId, String title, String description,
      Map<String, String> attributes,
      @Schema(description = "ACTIVE ou OUT_OF_STOCK (visível, mas não vendável)") ProductStatus status,
      List<StorefrontSkuResponse> skus) {
    static StorefrontProductResponse of(StorefrontProduct shown, Map<UUID, StockLevel> stock, Instant now) {
      var p = shown.product();
      return new StorefrontProductResponse(p.id(), p.storeId(), p.categoryId(), p.title(), p.description(),
          p.attributes(),
          p.effectiveStatus(ProductModels.anyAvailable(p, stock)),
          p.skus().stream()
              .map(s -> StorefrontSkuResponse.of(s, shown.offers().get(s.id()), stock.get(s.id()), now))
              .toList());
    }
  }
}
