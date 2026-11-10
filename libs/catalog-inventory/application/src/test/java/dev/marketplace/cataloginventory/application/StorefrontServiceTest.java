package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.marketplace.cataloginventory.application.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class StorefrontServiceTest {
  final Catalog catalog = new Catalog();
  final StorefrontService storefront = catalog.storefront;
  final ModerationService moderation = catalog.moderation;

  @Test
  void showsPublishedProductWithTheOfferOfEverySku() {
    Product product = catalog.active(sku("CAM-P", "P"), sku("CAM-M", "M"));
    StorefrontService.StorefrontProduct shown = item(storefront.get(TENANT, product.id()));
    assertEquals(product.id(), shown.product().id());
    assertEquals(Set.of(product.skus().get(0).id(), product.skus().get(1).id()), shown.offers().keySet());
  }

  @Test
  void hidesBlockedProductIneligibleSellerAndOtherTenant() {
    Product product = catalog.active(sku("CAM-P", "P"));
    item(moderation.block(moderator(), product.id(), Optional.empty(), List.of("prohibited-item"),
        Optional.empty()));
    failure(storefront.get(TENANT, product.id()), NotFoundException.class);
    item(moderation.unblock(moderator(), product.id(), Optional.empty()));
    item(moderation.approve(moderator(), product.id(), Optional.empty()));
    item(catalog.eligibility.record(verdict(catalog.storeId, SellerStatus.REJECTED)));
    failure(storefront.get(TENANT, product.id()), NotFoundException.class);
    failure(storefront.get(UUID.randomUUID(), product.id()), NotFoundException.class);
  }

  @Test
  void hidesDraft() {
    Product product = catalog.offered(catalog.draft(sku("CAM-P", "P")));
    failure(storefront.get(TENANT, product.id()), NotFoundException.class);
  }
}
