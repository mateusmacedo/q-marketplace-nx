package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.Money;
import dev.marketplace.cataloginventory.domain.category.AttributeDefinition;
import dev.marketplace.cataloginventory.domain.category.AttributeType;
import dev.marketplace.cataloginventory.domain.product.Dimensions;
import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import dev.marketplace.cataloginventory.domain.offer.Price;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

final class Fixtures {
  static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
  static final UUID TENANT = UUID.randomUUID();
  static final AttributeDefinition BRAND = new AttributeDefinition("marca", "Marca", AttributeType.TEXT, true, false,
      List.of());
  static final AttributeDefinition SIZE = new AttributeDefinition("tamanho", "Tamanho", AttributeType.OPTION, true,
      true, List.of("P", "M", "G"));
  static final Dimensions BOX = new Dimensions(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 300);
  static final ReservationService.Settings SETTINGS = new ReservationService.Settings(Duration.ofMinutes(15),
      Duration.ofHours(2), Duration.ofHours(24));

  private Fixtures() {
  }

  static Actor seller(UUID store) {
    return new Actor(TENANT, Optional.of(store), "seller-" + store, Set.of(Capability.MANAGE_OWN_PRODUCTS));
  }

  static Actor moderator() {
    return new Actor(TENANT, Optional.empty(), "mod", Set.of(Capability.MODERATE_PRODUCTS));
  }

  static Actor admin() {
    return new Actor(TENANT, Optional.empty(), "admin",
        Set.of(Capability.MANAGE_TAXONOMY, Capability.READ_TENANT_CATALOG,
            Capability.READ_SELLER_ELIGIBILITY, Capability.MANAGE_MODERATION_REASONS));
  }

  static SellerVerdict verdict(UUID store, SellerStatus status) {
    return new SellerVerdict(UUID.randomUUID(), TENANT, store, status, NOW);
  }

  static Actor checkout() {
    return new Actor(TENANT, Optional.empty(), "checkout", Set.of(Capability.MANAGE_RESERVATIONS));
  }

  static ProductService.NewSku sku(String code, String size) {
    return new ProductService.NewSku(code, Map.of("tamanho", size), true, BOX);
  }

  static Price price(String sale) {
    return new Price(null, Money.of(sale, "BRL"));
  }

  static <T> T item(Uni<T> uni) {
    return uni.subscribe().withSubscriber(UniAssertSubscriber.create()).awaitItem().getItem();
  }

  static <E extends Throwable> E failure(Uni<?> uni, Class<E> type) {
    return type.cast(uni.subscribe().withSubscriber(UniAssertSubscriber.create()).awaitFailure()
        .assertFailedWith(type).getFailure());
  }
}
