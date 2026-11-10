package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.inventory.StockLevel;
import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.Sku;
import io.smallrye.mutiny.Uni;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Saldo por SKU: leitura para quem vê o produto e ajuste do estoque físico pelo
 * seller dono.
 */
public final class InventoryService {
  private final InventoryRepository inventory;
  private final ProductAccess access;
  private final Clock clock;

  public InventoryService(InventoryRepository inventory, ProductRepository products, Clock clock) {
    this.inventory = inventory;
    this.access = new ProductAccess(products);
    this.clock = clock;
  }

  /** Saldos dos SKUs de produtos já autorizados para o chamador. */
  public Uni<Map<UUID, StockLevel>> stockOf(Collection<Product> products) {
    List<UUID> skuIds = products.stream().flatMap(p -> p.skus().stream()).map(Sku::id).toList();
    if (skuIds.isEmpty())
      return Uni.createFrom().item(Map.of());
    return inventory.levels(products.iterator().next().tenantId(), skuIds);
  }

  /**
   * Busca o saldo do SKU com a visibilidade do produto, como em
   * {@link ProductService#get}; SKU desconhecido ou sem saldo lança
   * {@link NotFoundException}.
   */
  public Uni<StockLevel> get(Actor actor, UUID skuId) {
    return access.visibleBySku(actor, skuId)
        .flatMap(product -> inventory.levels(product.tenantId(), List.of(skuId)))
        .map(levels -> Optional.ofNullable(levels.get(skuId))
            .orElseThrow(() -> new NotFoundException("Estoque")));
  }

  /**
   * Define o estoque físico do SKU em valor absoluto, com auditoria e evento
   * na outbox. Só o seller dono; arquivado: {@code invalid-transition};
   * abaixo do reservado: {@code stock-below-reserved}. {@code ifMatch}:
   * versão do saldo; ausente = sem checagem.
   */
  public Uni<StockLevel> adjust(Actor actor, UUID skuId, Optional<Long> ifMatch, int onHand) {
    return access.ownedBySku(actor, skuId).flatMap(product -> {
      int requested = StockLevel.requireOnHand(onHand);
      product.requireNotArchived("ADJUST_STOCK");
      return inventory.adjust(new StockAdjustment(product.tenantId(), product.storeId(), product.id(), skuId,
          requested), ifMatch, actor.subject(), clock.instant());
    });
  }
}
