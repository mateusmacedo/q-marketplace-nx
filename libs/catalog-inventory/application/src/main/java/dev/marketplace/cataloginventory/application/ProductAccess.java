package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.product.Product;
import io.smallrye.mutiny.Uni;
import java.util.Optional;
import java.util.UUID;

/**
 * Carrega o produto, por id ou pelo SKU, e aplica quem pode vê-lo ou alterá-lo:
 * quem lê o catálogo do tenant vê qualquer
 * loja; o seller, só a própria. Fora do alcance responde 404, sem revelar que o
 * produto existe.
 */
final class ProductAccess {
  final ProductRepository products;

  ProductAccess(ProductRepository products) {
    this.products = products;
  }

  /**
   * Carrega o produto que o chamador pode ver: qualquer loja do tenant para
   * quem lê o catálogo ou modera; só a própria para o seller com
   * {@code MANAGE_OWN_PRODUCTS}. Inexistente ou fora do alcance lança
   * {@link NotFoundException}, sem revelar a diferença.
   */
  Uni<Product> visible(Actor actor, UUID productId) {
    return products.findById(actor.tenantId(), productId).map(found -> requireVisible(actor, found));
  }

  /**
   * Como {@link #visible}, localizando o produto pelo SKU; SKU desconhecido
   * no tenant também lança {@link NotFoundException}.
   */
  Uni<Product> visibleBySku(Actor actor, UUID skuId) {
    return products.findBySku(actor.tenantId(), skuId).map(found -> requireVisible(actor, found));
  }

  /**
   * Carrega o produto do tenant sem checar loja nem capacidade, para quem já
   * tem o alcance garantido (moderador, vitrine pública); inexistente no
   * tenant lança {@link NotFoundException}.
   */
  Uni<Product> inTenant(UUID tenantId, UUID productId) {
    return products.findById(tenantId, productId)
        .map(found -> found.orElseThrow(ProductAccess::productNotFound));
  }

  /**
   * Carrega o produto da loja do seller para alteração. Exige
   * {@code MANAGE_OWN_PRODUCTS} e loja no token
   * ({@link AccessDeniedException}); inexistente ou de outra loja lança
   * {@link NotFoundException}.
   */
  Uni<Product> owned(Actor actor, UUID productId) {
    return Uni.createFrom().deferred(() -> {
      UUID store = requireSeller(actor);
      return products.findById(actor.tenantId(), productId).map(found -> requireOwned(store, found));
    });
  }

  /** Como {@link #owned}, localizando o produto pelo SKU. */
  Uni<Product> ownedBySku(Actor actor, UUID skuId) {
    return Uni.createFrom().deferred(() -> {
      UUID store = requireSeller(actor);
      return products.findBySku(actor.tenantId(), skuId).map(found -> requireOwned(store, found));
    });
  }

  /**
   * Como {@link #ownedBySku(Actor, UUID)}, e logo após a carga exige produto
   * não arquivado para {@code operation} ({@code invalid-transition}).
   */
  Uni<Product> ownedBySku(Actor actor, UUID skuId, String operation) {
    return ownedBySku(actor, skuId).invoke(product -> product.requireNotArchived(operation));
  }

  /**
   * Loja que a listagem de produtos consulta: quem lê o catálogo do tenant
   * filtra por {@code storeFilter} (vazio = todas); o seller vê só a própria
   * loja, como em {@link #requireSeller}.
   */
  static Optional<UUID> listScope(Actor actor, Optional<UUID> storeFilter) {
    if (actor.canReadTenantCatalog())
      return storeFilter;
    return Optional.of(requireSeller(actor));
  }

  /**
   * Exige {@code MANAGE_OWN_PRODUCTS} e loja no token, nesta ordem, e devolve a
   * loja; falta de qualquer um lança {@link AccessDeniedException}.
   */
  static UUID requireSeller(Actor actor) {
    actor.require(Capability.MANAGE_OWN_PRODUCTS);
    return actor.requireStore();
  }

  private static Product requireVisible(Actor actor, Optional<Product> found) {
    return found.filter(p -> canSee(actor, p)).orElseThrow(ProductAccess::productNotFound);
  }

  private static Product requireOwned(UUID store, Optional<Product> found) {
    return found.filter(p -> p.storeId().equals(store)).orElseThrow(ProductAccess::productNotFound);
  }

  private static boolean canSee(Actor actor, Product product) {
    if (actor.canReadTenantCatalog())
      return true;
    return actor.can(Capability.MANAGE_OWN_PRODUCTS) && actor.ownsStore(product.storeId());
  }

  private static NotFoundException productNotFound() {
    return new NotFoundException("Produto");
  }
}
