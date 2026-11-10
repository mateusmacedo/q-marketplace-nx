package dev.marketplace.cataloginventory;

import dev.marketplace.cataloginventory.config.CatalogConfig;
import dev.marketplace.cataloginventory.application.*;
import dev.marketplace.cataloginventory.cache.ModerationReasonCache;
import dev.marketplace.cataloginventory.cache.TaxonomyCache;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;

/**
 * Raiz de composição: liga os casos de uso (sem framework) aos adaptadores. As leituras de categoria passam pelo cache
 * da taxonomia ({@link TaxonomyCache}) e as do catálogo de motivos, pelo {@link ModerationReasonCache}; as invalidações
 * ficam nos decorators das portas.
 */
public class CatalogBeans {
  // WHY: @Singleton evita client proxy; os serviços de aplicação são finais e não
  // podem ser proxiados.
  @Produces
  @Singleton
  Clock clock() {
    return Clock.systemUTC();
  }

  @Produces
  @Singleton
  CategoryService categoryService(CategoryRepository categories, TaxonomyCache taxonomy) {
    return new CategoryService(taxonomy.over(categories));
  }

  @Produces
  @Singleton
  ProductService productService(ProductRepository products, CategoryRepository categories,
      SellerEligibilityRepository sellers,
      OfferRepository offers, TaxonomyCache taxonomy, Clock clock) {
    return new ProductService(products, taxonomy.over(categories), sellers, offers, clock);
  }

  @Produces
  @Singleton
  ModerationService moderationService(ProductRepository products, CategoryRepository categories,
      SellerEligibilityRepository sellers,
      OfferRepository offers, ModerationReasonRepository reasons, TaxonomyCache taxonomy,
      ModerationReasonCache reasonCache, Clock clock) {
    return new ModerationService(products, taxonomy.over(categories), sellers, offers, reasonCache.over(reasons),
        clock);
  }

  @Produces
  @Singleton
  ModerationReasonService moderationReasonService(ModerationReasonRepository reasons,
      ModerationReasonCache reasonCache, Clock clock) {
    return new ModerationReasonService(reasonCache.over(reasons), clock);
  }

  @Produces
  @Singleton
  OfferService offerService(ProductRepository products, OfferRepository offers, Clock clock) {
    return new OfferService(products, offers, clock);
  }

  @Produces
  @Singleton
  InventoryService inventoryService(InventoryRepository inventory, ProductRepository products, Clock clock) {
    return new InventoryService(inventory, products, clock);
  }

  @Produces
  @Singleton
  ProductImportService productImportService(ProductImportRepository imports, ProductRepository products,
      CategoryRepository categories, OfferRepository offers, ProductService productService,
      OfferService offerService, InventoryService inventoryService, TaxonomyCache taxonomy, Clock clock) {
    return new ProductImportService(imports, products, taxonomy.over(categories), offers, productService,
        offerService, inventoryService, clock);
  }

  @Produces
  @Singleton
  CatalogStatsService catalogStatsService(CatalogStatsRepository stats, Clock clock) {
    return new CatalogStatsService(stats, clock);
  }

  @Produces
  @Singleton
  StorefrontService storefrontService(ProductRepository products, SellerEligibilityRepository sellers,
      OfferRepository offers) {
    return new StorefrontService(products, sellers, offers);
  }

  @Produces
  @Singleton
  ProductActions productActions(SellerEligibilityRepository sellers) {
    return new ProductActions(sellers);
  }

  @Produces
  @Singleton
  SellerEligibilityService sellerEligibilityService(SellerEligibilityRepository sellers) {
    return new SellerEligibilityService(sellers);
  }

  @Produces
  @Singleton
  ReservationService reservationService(ReservationRepository reservations, InventoryRepository inventory,
      CatalogConfig config,
      Clock clock) {
    CatalogConfig.Reservations r = config.reservations();
    return new ReservationService(reservations, inventory,
        new ReservationService.Settings(r.defaultTtl(), r.maxTtl(), r.maxLifetime()),
        clock);
  }
}
