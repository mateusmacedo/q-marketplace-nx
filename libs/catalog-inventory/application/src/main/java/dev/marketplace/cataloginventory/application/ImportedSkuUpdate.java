package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.application.ImportResult.ImportOutcome;
import dev.marketplace.cataloginventory.domain.inventory.StockLevel;
import dev.marketplace.cataloginventory.domain.offer.Offer;
import dev.marketplace.cataloginventory.domain.offer.Price;
import dev.marketplace.cataloginventory.domain.product.Product;
import io.smallrye.mutiny.Uni;
import java.util.Optional;
import java.util.UUID;
import static dev.marketplace.cataloginventory.application.ImportSteps.INVALID_ROW;
import static dev.marketplace.cataloginventory.application.ImportSteps.failed;

/**
 * Linha da planilha de um SKU que já existe na loja: aplica só preço e estoque, cada um apenas se mudou, pelos casos
 * de uso de oferta e estoque, como o seller que enviou o lote.
 */
public final class ImportedSkuUpdate {
  private static final String IMPORT = "IMPORT";
  private final ProductAccess access;
  private final OfferRepository offers;
  private final OfferService offerService;
  private final InventoryService inventoryService;

  public ImportedSkuUpdate(ProductRepository products, OfferRepository offers, OfferService offerService,
      InventoryService inventoryService) {
    this.access = new ProductAccess(products);
    this.offers = offers;
    this.offerService = offerService;
    this.inventoryService = inventoryService;
  }

  /**
   * Aplica ao SKU existente só preço e estoque, cada um apenas se mudou. Produto arquivado:
   * {@code invalid-transition}. {@code UPDATED} se algo mudou, senão {@code UNCHANGED}; recusa de negócio vira a
   * linha {@code FAILED}.
   */
  Uni<ImportResult> apply(Actor seller, ImportRow row, UUID skuId) {
    if (row.error() != null)
      return Uni.createFrom().item(ImportResult.failed(row, null, INVALID_ROW, row.error()));
    return access.ownedBySku(seller, skuId).flatMap(product -> Uni.createFrom().deferred(() -> {
      product.requireNotArchived(IMPORT);
      Price price = ImportSteps.price(row);
      int onHand = StockLevel.requireOnHand(row.onHand());
      return changePrice(seller, product, skuId, price)
          .flatMap(priceChanged -> changeStock(seller, skuId, onHand)
              .map(stockChanged -> priceChanged || stockChanged));
    }).map(changed -> ImportResult.succeeded(row,
        Boolean.TRUE.equals(changed) ? ImportOutcome.UPDATED : ImportOutcome.UNCHANGED, product.id()))
        .onFailure(ImportSteps::isRefusal).recoverWithItem(e -> failed(row, product.id(), e)))
        .onFailure(ImportSteps::isRefusal).recoverWithItem(e -> failed(row, null, e));
  }

  /**
   * Troca o preço só se difere do atual, com a versão lida da oferta como
   * If-Match implícito; sem oferta, cria. Devolve se houve mudança.
   */
  private Uni<Boolean> changePrice(Actor seller, Product product, UUID skuId, Price price) {
    return offers.ofSku(product.tenantId(), skuId).flatMap(current -> {
      if (current.isPresent() && current.get().price().equals(price))
        return Uni.createFrom().item(false);
      return offerService.define(seller, skuId, current.map(Offer::version), price).replaceWith(true);
    });
  }

  /**
   * Define o estoque absoluto só se difere do atual, sem If-Match: reservas
   * concorrentes também incrementam a versão do saldo.
   */
  private Uni<Boolean> changeStock(Actor seller, UUID skuId, int onHand) {
    return inventoryService.get(seller, skuId).flatMap(level -> level.onHand() == onHand
        ? Uni.createFrom().item(false)
        : inventoryService.adjust(seller, skuId, Optional.empty(), onHand).replaceWith(true));
  }
}
