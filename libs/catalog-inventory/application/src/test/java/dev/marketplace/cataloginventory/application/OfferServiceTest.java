package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.InvalidTransitionException;
import dev.marketplace.cataloginventory.domain.Money;
import dev.marketplace.cataloginventory.domain.offer.Offer;
import dev.marketplace.cataloginventory.domain.offer.Price;
import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static dev.marketplace.cataloginventory.application.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class OfferServiceTest {
  final Catalog catalog = new Catalog();
  final ProductService products = catalog.products;
  final OfferService offers = catalog.offers;
  final Actor seller = catalog.seller;
  final Product product = catalog.draft(sku("CAM-P", "P"));
  final UUID sku = product.skus().get(0).id();

  @Test
  void firstDefinitionCreatesTheOfferInTheProductCurrency() {
    OfferService.Defined defined = item(offers.define(seller, sku, Optional.empty(), price("50")));
    assertTrue(defined.created());
    assertEquals("BRL", defined.offer().currency());
    assertEquals(product.id(), defined.offer().productId());
    assertEquals(1, defined.offer().priceVersion());
    var mismatch = failure(offers.define(seller, sku, Optional.of(0L), new Price(null, Money.of("9", "USD"))),
        DomainRuleViolation.class);
    assertEquals("currency-mismatch", mismatch.code());
  }

  @Test
  void changingAnExistingPriceRequiresIfMatchAndIfMatchOnAMissingOfferFails() {
    failure(offers.define(seller, sku, Optional.of(3L), price("50")), VersionMismatchException.class);
    item(offers.define(seller, sku, Optional.empty(), price("50")));
    failure(offers.define(seller, sku, Optional.empty(), price("45")), PreconditionRequiredException.class);
    failure(offers.define(seller, sku, Optional.of(7L), price("45")), VersionMismatchException.class);
    OfferService.Defined changed = item(offers.define(seller, sku, Optional.of(0L), price("45")));
    assertFalse(changed.created());
    assertEquals(2, changed.offer().priceVersion());
    assertEquals(1, changed.offer().version());
  }

  @Test
  void priceAndPromotionDoNotSendTheProductBackToModeration() {
    item(offers.define(seller, sku, Optional.empty(), new Price(Money.of("70", "BRL"), Money.of("60", "BRL"))));
    catalog.approveSeller();
    catalog.publish(product);
    Offer promoted = item(
        offers.schedulePromotion(seller, sku, Optional.empty(), UUID.randomUUID(), Money.of("40", "BRL"),
            NOW.minus(Duration.ofMinutes(1)), NOW.plus(Duration.ofDays(1))));
    assertEquals(Money.of("40", "BRL"), promoted.effectivePrice(NOW).salePrice());
    assertEquals(2, promoted.effectivePrice(NOW).priceVersion());
    assertEquals(ProductStatus.ACTIVE, item(products.get(seller, product.id())).status());
    UUID promotion = promoted.promotions().get(0).id();
    assertEquals(Money.of("60", "BRL"),
        item(offers.cancelPromotion(seller, sku, promotion, Optional.of(promoted.version())))
            .effectivePrice(NOW).salePrice());
  }

  @Test
  void onlyTheOwnerChangesAndEveryoneWhoSeesTheProductReads() {
    item(offers.define(seller, sku, Optional.empty(), price("50")));
    failure(offers.define(seller(UUID.randomUUID()), sku, Optional.of(0L), price("40")), NotFoundException.class);
    failure(offers.define(moderator(), sku, Optional.of(0L), price("40")), AccessDeniedException.class);
    assertEquals(sku, item(offers.get(moderator(), sku)).skuId());
    failure(offers.get(seller(UUID.randomUUID()), sku), NotFoundException.class);
    failure(offers.get(seller, UUID.randomUUID()), NotFoundException.class);
  }

  @Test
  void archivedProductFreezesItsOffers() {
    item(offers.define(seller, sku, Optional.empty(), price("50")));
    item(products.archive(seller, product.id(), Optional.empty()));
    failure(offers.define(seller, sku, Optional.of(0L), price("40")), InvalidTransitionException.class);
    failure(offers.schedulePromotion(seller, sku, Optional.empty(), UUID.randomUUID(), Money.of("40", "BRL"), NOW,
        NOW.plus(Duration.ofDays(1))), InvalidTransitionException.class);
  }
}
