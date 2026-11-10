package dev.marketplace.cataloginventory;

import dev.marketplace.cataloginventory.application.CategoryRepository;
import dev.marketplace.cataloginventory.application.ModerationReasonRepository;
import dev.marketplace.cataloginventory.application.ModerationReasonService;
import dev.marketplace.cataloginventory.application.ModerationService;
import dev.marketplace.cataloginventory.application.OfferRepository;
import dev.marketplace.cataloginventory.application.ProductRepository;
import dev.marketplace.cataloginventory.application.SellerEligibilityRepository;
import dev.marketplace.cataloginventory.application.SellerEligibilityService;
import dev.marketplace.cataloginventory.cache.CacheBacked;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

/**
 * Raiz de composição da moderação: a fila e as decisões, o catálogo de motivos do tenant, lido pela porta em cache
 * ({@link CacheBacked}), e a situação da loja que chega do contexto KYB/KYC.
 */
public class ModerationBeans {
  @Produces
  @ApplicationScoped
  ModerationService moderationService(ProductRepository products, @CacheBacked CategoryRepository categories,
      SellerEligibilityRepository sellers, OfferRepository offers, @CacheBacked ModerationReasonRepository reasons,
      Clock clock) {
    return new ModerationService(products, categories, sellers, offers, reasons, clock);
  }

  @Produces
  @ApplicationScoped
  ModerationReasonService moderationReasonService(@CacheBacked ModerationReasonRepository reasons, Clock clock) {
    return new ModerationReasonService(reasons, clock);
  }

  @Produces
  @ApplicationScoped
  SellerEligibilityService sellerEligibilityService(SellerEligibilityRepository sellers) {
    return new SellerEligibilityService(sellers);
  }
}
