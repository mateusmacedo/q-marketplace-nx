package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static dev.marketplace.cataloginventory.application.Fixtures.*;

/**
 * Serviços ligados ao mesmo armazenamento em memória, com uma loja e uma
 * categoria prontas para os cenários.
 */
final class Catalog {
    final InMemoryStore store = new InMemoryStore();
    final MutableClock clock = new MutableClock(NOW);
    final CategoryService categories = new CategoryService(store);
    final ProductService products = new ProductService(store, store, store, store, clock);
    final ModerationService moderation = new ModerationService(store, store, store, store, clock);
    final OfferService offers = new OfferService(store, store, clock);
    final InventoryService inventory = new InventoryService(store, store, clock);
    final ReservationService reservations = new ReservationService(store, store, SETTINGS, clock);
    final SellerEligibilityService eligibility = new SellerEligibilityService(store);
    final StorefrontService storefront = new StorefrontService(store, store, store);
    final ProductImportService imports = new ProductImportService(store, store, store, store, products, offers,
            inventory, clock);
    final UUID storeId = UUID.randomUUID();
    final Actor seller = seller(storeId);
    final UUID category = item(
            categories.create(admin(), Optional.empty(), "camisetas", "Camisetas", List.of(BRAND, SIZE)))
            .category().id();

    Product draft(ProductService.NewSku... skus) {
        return item(products.create(seller, category, "BRL", "Camiseta", "Algodão", Map.of("marca", "Acme"),
                List.of(skus)));
    }

    void offer(UUID skuId, String salePrice) {
        item(offers.define(seller, skuId, Optional.empty(), price(salePrice)));
    }

    Product offered(Product product) {
        product.skus().forEach(sku -> offer(sku.id(), "50"));
        return product;
    }

    void approveSeller() {
        item(eligibility.record(verdict(storeId, SellerStatus.APPROVED)));
    }

    /**
     * Envia para moderação e aprova; exige seller aprovado e oferta em todo SKU.
     */
    Product publish(Product product) {
        item(products.submit(seller, product.id(), Optional.empty()));
        return item(moderation.approve(moderator(), product.id(), Optional.empty()));
    }

    Product active(ProductService.NewSku... skus) {
        approveSeller();
        return publish(offered(draft(skus)));
    }
}
