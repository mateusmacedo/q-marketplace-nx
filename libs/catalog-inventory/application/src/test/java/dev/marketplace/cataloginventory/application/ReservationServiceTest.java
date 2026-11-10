package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.InvalidTransitionException;
import dev.marketplace.cataloginventory.domain.Money;
import dev.marketplace.cataloginventory.domain.inventory.*;
import dev.marketplace.cataloginventory.domain.product.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static dev.marketplace.cataloginventory.application.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ReservationServiceTest {
  final Catalog catalog = new Catalog();
  final InMemoryStore store = catalog.store;
  final MutableClock clock = catalog.clock;
  final ProductService products = catalog.products;
  final InventoryService inventory = catalog.inventory;
  final ReservationService reservations = catalog.reservations;
  final Actor seller = catalog.seller;
  final Product product;
  final UUID skuP, skuM;

  ReservationServiceTest() {
    catalog.approveSeller();
    product = catalog.draft(sku("CAM-P", "P"), sku("CAM-M", "M"));
    skuP = product.skus().get(0).id();
    skuM = product.skus().get(1).id();
    catalog.offer(skuP, "50");
    catalog.offer(skuM, "55");
    catalog.publish(product);
    item(inventory.adjust(seller, skuP, Optional.empty(), 2));
    item(inventory.adjust(seller, skuM, Optional.empty(), 1));
  }

  ReservationService.Request request(String key, RequestedItem... items) {
    return new ReservationService.Request(key, "pedido-1", Optional.empty(), List.of(items));
  }

  RequestedItem p(int q) {
    return new RequestedItem(skuP, q, Money.of("50", "BRL"));
  }

  RequestedItem m(int q) {
    return new RequestedItem(skuM, q, Money.of("55", "BRL"));
  }

  StockLevel level(UUID sku) {
    return store.stock.get(sku);
  }

  @Test
  void reservesAllOrNothing() {
    Reservation r = item(reservations.reserve(checkout(), request("k1", p(2), m(1)))).reservation();
    assertEquals(ReservationStatus.ACTIVE, r.status());
    assertEquals(0, level(skuP).available());
    var refusal = failure(reservations.reserve(checkout(), request("k2", p(1))), ReservationRefusal.class);
    assertEquals("insufficient-stock", refusal.code());
    item(reservations.release(checkout(), r.id()));
    var partial = failure(reservations.reserve(checkout(), request("k3", p(1), m(2))), ReservationRefusal.class);
    assertEquals(List.of("insufficient-stock:" + skuM), partial.reasons());
    assertEquals(2, level(skuP).available());
  }

  @Test
  void sameKeyReplaysAndOtherPayloadIsRefused() {
    var first = item(reservations.reserve(checkout(), request("k1", p(1))));
    var replay = item(reservations.reserve(checkout(), request("k1", p(1))));
    assertFalse(first.replayed());
    assertTrue(replay.replayed());
    assertEquals(first.reservation().id(), replay.reservation().id());
    assertEquals(1, level(skuP).reserved());
    var reused = failure(reservations.reserve(checkout(), request("k1", p(2))), DomainRuleViolation.class);
    assertEquals("idempotency-key-reused", reused.code());
    failure(reservations.reserve(checkout(), request(" ", p(1))), InvalidInputException.class);
  }

  @Test
  void commitConsumesStockOnceAndReleaseIsIdempotent() {
    Reservation r = item(reservations.reserve(checkout(), request("k1", p(2)))).reservation();
    Reservation committed = item(reservations.commit(checkout(), r.id()));
    assertEquals(ReservationStatus.COMMITTED, committed.status());
    assertEquals(new StockLevel(skuP, 0, 0, 2, level(skuP).version()), level(skuP));
    assertEquals(ReservationStatus.COMMITTED, item(reservations.commit(checkout(), r.id())).status());
    assertEquals(2, level(skuP).sold());
    failure(reservations.release(checkout(), r.id()), InvalidTransitionException.class);

    item(inventory.adjust(seller, skuP, Optional.empty(), 3));
    Reservation other = item(reservations.reserve(checkout(), request("k2", p(3)))).reservation();
    item(reservations.release(checkout(), other.id()));
    assertEquals(ReservationStatus.RELEASED, item(reservations.release(checkout(), other.id())).status());
    assertEquals(new StockLevel(skuP, 3, 0, 2, level(skuP).version()), level(skuP));
    assertEquals("reservation-not-active",
        failure(reservations.commit(checkout(), other.id()), ReservationRefusal.class).code());
  }

  @Test
  void expirationReturnsStockAndBlocksLateCommit() {
    Reservation r = item(reservations.reserve(checkout(), request("k1", p(2)))).reservation();
    clock.advance(Duration.ofMinutes(16));
    assertEquals("reservation-expired",
        failure(reservations.commit(checkout(), r.id()), ReservationRefusal.class).code());
    assertEquals(1, item(reservations.expireDue(100)));
    assertEquals(ReservationStatus.EXPIRED, item(reservations.get(checkout(), r.id())).status());
    assertEquals(2, level(skuP).available());
    assertEquals(ReservationStatus.EXPIRED, item(reservations.release(checkout(), r.id())).status());
    assertEquals(2, level(skuP).available());
  }

  @Test
  void extensionRespectsLifetime() {
    Reservation r = item(reservations.reserve(checkout(), request("k1", p(1)))).reservation();
    clock.advance(Duration.ofMinutes(10));
    Reservation extended = item(reservations.extend(checkout(), r.id(), Duration.ofMinutes(30)));
    assertEquals(clock.instant().plus(Duration.ofMinutes(30)), extended.expiresAt());
    clock.advance(Duration.ofHours(23).plusMinutes(45));
    failure(reservations.extend(checkout(), r.id(), Duration.ofMinutes(30)), ReservationRefusal.class);
    failure(reservations.extend(checkout(), r.id(), Duration.ofHours(3)), DomainRuleViolation.class);
  }

  @Test
  void refusesUnavailableProductAndChangedPrice() {
    item(products.pause(seller, product.id(), Optional.empty()));
    assertEquals("product-unavailable",
        failure(reservations.reserve(checkout(), request("k1", p(1))), ReservationRefusal.class).code());
    item(products.resume(seller, product.id(), Optional.empty()));
    var priceChanged = failure(reservations.reserve(checkout(),
        request("k2", new RequestedItem(skuP, 1, Money.of("49.99", "BRL")))), ReservationRefusal.class);
    assertEquals("price-changed", priceChanged.code());
  }

  @Test
  void onlyReservationServicesCanReserve() {
    failure(reservations.reserve(seller, request("k1", p(1))), AccessDeniedException.class);
    failure(reservations.get(seller, UUID.randomUUID()), AccessDeniedException.class);
    failure(reservations.get(checkout(), UUID.randomUUID()), NotFoundException.class);
  }

  @Test
  void requestHashIgnoresItemOrder() {
    assertEquals(ReservationService.hash(request("k", p(1), m(1))),
        ReservationService.hash(request("k", m(1), p(1))));
    assertNotEquals(ReservationService.hash(request("k", p(1))), ReservationService.hash(request("k", p(2))));
  }
}
