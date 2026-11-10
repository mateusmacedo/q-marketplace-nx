package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.offer.Offer;
import dev.marketplace.cataloginventory.domain.product.Product;
import io.smallrye.mutiny.Uni;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Leitura pública e autoritativa da vitrine. WHY: o índice de busca é projeção
 * e pode atrasar; esta consulta reavalia
 * estado e seller a cada chamada, então um bloqueio tira o produto do ar na
 * hora (PRD).
 */
public final class StorefrontService {
  /** Produto visível com as ofertas dos seus SKUs, indexadas pelo SKU. */
  public record StorefrontProduct(Product product, Map<UUID, Offer> offers) {
  }

  private final ProductAccess access;
  private final SellerEligibilityRepository sellers;
  private final OfferRepository offers;

  public StorefrontService(ProductRepository products, SellerEligibilityRepository sellers, OfferRepository offers) {
    this.access = new ProductAccess(products);
    this.sellers = sellers;
    this.offers = offers;
  }

  /**
   * Busca o produto na vitrine pública, sem chamador autenticado, reavaliando
   * a cada chamada o estado do produto e do seller. Inexistente, fora de
   * {@code ACTIVE}/{@code OUT_OF_STOCK} ou de seller não {@code APPROVED}
   * lança {@link NotFoundException}. Traz as ofertas dos SKUs por SKU.
   */
  public Uni<StorefrontProduct> get(UUID tenantId, UUID productId) {
    return access.inTenant(tenantId, productId)
        .flatMap(product -> Uni.combine().all()
            .unis(sellers.statusOf(tenantId, product.storeId()), offers.ofProduct(tenantId, product.id()))
            .asTuple().map(read -> {
              if (!product.isPubliclyVisible(read.getItem1()))
                throw new NotFoundException("Produto");
              Map<UUID, Offer> bySku = read.getItem2().stream()
                  .collect(Collectors.toMap(Offer::skuId, Function.identity()));
              return new StorefrontProduct(product, bySku);
            }));
  }
}
