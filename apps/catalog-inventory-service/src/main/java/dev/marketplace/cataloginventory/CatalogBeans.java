package dev.marketplace.cataloginventory;

import dev.marketplace.cataloginventory.application.CatalogStatsRepository;
import dev.marketplace.cataloginventory.application.CatalogStatsService;
import dev.marketplace.cataloginventory.application.CategoryRepository;
import dev.marketplace.cataloginventory.application.CategoryService;
import dev.marketplace.cataloginventory.application.InventoryRepository;
import dev.marketplace.cataloginventory.application.InventoryService;
import dev.marketplace.cataloginventory.application.OfferRepository;
import dev.marketplace.cataloginventory.application.OfferService;
import dev.marketplace.cataloginventory.application.ProductActions;
import dev.marketplace.cataloginventory.application.ProductRepository;
import dev.marketplace.cataloginventory.application.ProductService;
import dev.marketplace.cataloginventory.application.SellerEligibilityRepository;
import dev.marketplace.cataloginventory.application.StorefrontService;
import dev.marketplace.cataloginventory.cache.CacheBacked;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

/**
 * Raiz de composição do catálogo: liga os casos de uso (sem framework) de categoria, produto, oferta, estoque, vitrine
 * e indicadores aos adaptadores. As leituras de categoria chegam pela porta em cache ({@link CacheBacked}); moderação,
 * importação e reservas ficam em {@link ModerationBeans}, {@link ImportBeans} e {@link ReservationBeans}.
 */
public class CatalogBeans {
  @Produces
  @ApplicationScoped
  Clock clock() {
    return Clock.systemUTC();
  }

  @Produces
  @ApplicationScoped
  CategoryService categoryService(@CacheBacked CategoryRepository categories) {
    return new CategoryService(categories);
  }

  @Produces
  @ApplicationScoped
  ProductService productService(ProductRepository products, @CacheBacked CategoryRepository categories,
      SellerEligibilityRepository sellers, OfferRepository offers, Clock clock) {
    return new ProductService(products, categories, sellers, offers, clock);
  }

  @Produces
  @ApplicationScoped
  OfferService offerService(ProductRepository products, OfferRepository offers, Clock clock) {
    return new OfferService(products, offers, clock);
  }

  @Produces
  @ApplicationScoped
  InventoryService inventoryService(InventoryRepository inventory, ProductRepository products, Clock clock) {
    return new InventoryService(inventory, products, clock);
  }

  @Produces
  @ApplicationScoped
  CatalogStatsService catalogStatsService(CatalogStatsRepository stats, Clock clock) {
    return new CatalogStatsService(stats, clock);
  }

  @Produces
  @ApplicationScoped
  StorefrontService storefrontService(ProductRepository products, SellerEligibilityRepository sellers,
      OfferRepository offers) {
    return new StorefrontService(products, sellers, offers);
  }

  @Produces
  @ApplicationScoped
  ProductActions productActions(SellerEligibilityRepository sellers) {
    return new ProductActions(sellers);
  }
}
