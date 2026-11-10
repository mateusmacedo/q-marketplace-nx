package dev.marketplace.cataloginventory.domain.inventory;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.Money;
import dev.marketplace.cataloginventory.domain.offer.Offer;
import dev.marketplace.cataloginventory.domain.offer.Price;
import dev.marketplace.cataloginventory.domain.offer.Promotion;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReservationPolicyTest {
  static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
  static final Duration TTL = Duration.ofMinutes(15);
  static final Duration MAX = Duration.ofHours(2);
  static final UUID TENANT = UUID.randomUUID();

  static Offer offer(String price) {
    return Offer.create(UUID.randomUUID(), UUID.randomUUID(), TENANT, UUID.randomUUID(), "BRL",
        new Price(null, Money.of(price, "BRL")), NOW);
  }

  static SkuCandidate candidate(Offer offer, ProductStatus status, SellerStatus seller) {
    return new SkuCandidate(offer.productId(), offer.storeId(), status, seller, offer);
  }

  static Reservation open(List<RequestedItem> items, Map<UUID, SkuCandidate> candidates) {
    return ReservationPolicy.open(UUID.randomUUID(), TENANT, "key", "hash", "pedido-1", items, candidates,
        NOW, TTL,
        MAX);
  }

  static RequestedItem item(Offer offer, int quantity, String price) {
    return new RequestedItem(offer.skuId(), quantity, Money.of(price, "BRL"));
  }

  @Test
  void lockOrderComparesUuidBytesWithoutSignLikePostgres() {
    UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");
    UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID highTail = UUID.fromString("00000000-0000-0000-8000-000000000000");
    assertTrue(high.compareTo(low) < 0, "UUID.compareTo compara com sinal");
    assertEquals(List.of(low, highTail, high),
        List.of(high, highTail, low).stream().sorted(ReservationPolicy.LOCK_ORDER).toList());
    Offer first = Offer.create(low, UUID.randomUUID(), TENANT, UUID.randomUUID(), "BRL",
        new Price(null, Money.of("10", "BRL")), NOW);
    Offer last = Offer.create(high, UUID.randomUUID(), TENANT, UUID.randomUUID(), "BRL",
        new Price(null, Money.of("10", "BRL")), NOW);
    Reservation r = open(List.of(item(last, 1, "10"), item(first, 1, "10")),
        Map.of(low, candidate(first, ProductStatus.ACTIVE, SellerStatus.APPROVED),
            high, candidate(last, ProductStatus.ACTIVE, SellerStatus.APPROVED)));
    assertEquals(List.of(low, high), r.items().stream().map(ReservationItem::skuId).toList());
  }

  @Test
  void snapshotsEffectivePriceAndSortsItemsBySku() {
    Offer a = offer("10"), b = offer("20");
    Map<UUID, SkuCandidate> candidates = Map.of(a.skuId(),
        candidate(a, ProductStatus.ACTIVE, SellerStatus.APPROVED),
        b.skuId(), candidate(b, ProductStatus.ACTIVE, SellerStatus.APPROVED));
    Reservation r = open(List.of(item(b, 2, "20"), item(a, 1, "10")), candidates);
    assertEquals(ReservationStatus.ACTIVE, r.status());
    assertEquals(NOW.plus(TTL), r.expiresAt());
    assertEquals(Collections.min(List.of(a.skuId(), b.skuId()), ReservationPolicy.LOCK_ORDER),
        r.items().get(0).skuId());
    assertEquals(1,
        r.items().stream().filter(i -> i.skuId().equals(a.skuId())).findFirst().orElseThrow()
            .priceVersion());
  }

  @Test
  void refusesWithEveryReason() {
    Offer paused = offer("10"), rejected = offer("10"), repriced = offer("10");
    UUID unpublished = UUID.randomUUID();
    UUID unknown = UUID.randomUUID();
    Map<UUID, SkuCandidate> candidates = Map.of(
        paused.skuId(),
        candidate(paused, ProductStatus.PAUSED_BY_SELLER, SellerStatus.APPROVED),
        rejected.skuId(), candidate(rejected, ProductStatus.ACTIVE, SellerStatus.REJECTED),
        repriced.skuId(), candidate(repriced, ProductStatus.ACTIVE, SellerStatus.APPROVED),
        unpublished, new SkuCandidate(UUID.randomUUID(), UUID.randomUUID(), ProductStatus.DRAFT,
            SellerStatus.APPROVED, null));
    var error = assertThrows(ReservationRefusal.class,
        () -> open(List.of(item(paused, 1, "10"), item(rejected, 1, "10"),
            item(repriced, 1, "9.99"),
            new RequestedItem(unpublished, 1, Money.of("1", "BRL")),
            new RequestedItem(unknown, 1, Money.of("1", "BRL"))), candidates));
    assertEquals("reservation-refused", error.code());
    assertEquals(List.of("product-unavailable:" + paused.skuId(), "seller-not-eligible:" + rejected.skuId(),
        "price-changed:" + repriced.skuId(), "product-unavailable:" + unpublished,
        "unknown-sku:" + unknown),
        error.reasons());
  }

  @Test
  void singleCategoryRefusalUsesItsCode() {
    Offer o = offer("10");
    var error = assertThrows(ReservationRefusal.class, () -> open(List.of(item(o, 1, "11")),
        Map.of(o.skuId(), candidate(o, ProductStatus.ACTIVE, SellerStatus.APPROVED))));
    assertEquals("price-changed", error.code());
  }

  @Test
  void promotionWindowChangesTheExpectedPrice() {
    Offer o = offer("100");
    Map<UUID, SkuCandidate> candidates = Map.of(o.skuId(),
        candidate(o, ProductStatus.ACTIVE, SellerStatus.APPROVED));
    var promo = new Promotion(UUID.randomUUID(), Money.of("80", "BRL"), NOW.minusSeconds(60),
        NOW.plusSeconds(60));
    Offer promoted = Offer.restore(o.skuId(), o.productId(), TENANT, o.storeId(), "BRL", o.price(), 2,
        List.of(promo), 1, NOW, NOW);
    Map<UUID, SkuCandidate> withPromo = Map.of(o.skuId(),
        candidate(promoted, ProductStatus.ACTIVE, SellerStatus.APPROVED));
    Reservation r = open(List.of(item(o, 1, "80")), withPromo);
    assertEquals(Money.of("80", "BRL"), r.items().get(0).unitPrice());
    assertEquals(2, r.items().get(0).priceVersion());
    assertThrows(ReservationRefusal.class, () -> open(List.of(item(o, 1, "80")), candidates));
  }

  @Test
  void validatesShape() {
    Offer o = offer("10");
    Map<UUID, SkuCandidate> c = Map.of(o.skuId(),
        candidate(o, ProductStatus.ACTIVE, SellerStatus.APPROVED));
    RequestedItem item = item(o, 1, "10");
    assertThrows(DomainRuleViolation.class, () -> open(List.of(), c));
    assertEquals(List.of("duplicate-sku:" + o.skuId()),
        assertThrows(ReservationRefusal.class, () -> open(List.of(item, item), c)).reasons());
    assertThrows(DomainRuleViolation.class,
        () -> ReservationPolicy.open(UUID.randomUUID(), TENANT, "k", "h", null, List.of(item),
            c, NOW,
            Duration.ofHours(3), MAX));
    assertThrows(DomainRuleViolation.class, () -> new RequestedItem(o.skuId(), 0, Money.of("10", "BRL")));
  }

  @Test
  void insufficientStockListsSortedSkus() {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    ReservationRefusal refusal = ReservationPolicy.insufficientStock(List.of(b, a));
    assertEquals("insufficient-stock", refusal.code());
    assertEquals(new TreeSet<>(Set.of(a, b)).stream().map(id -> "insufficient-stock:" + id).toList(),
        refusal.reasons());
  }

  @Test
  void overdueReservationCannotBeConfirmed() {
    Offer o = offer("10");
    Reservation r = open(List.of(item(o, 1, "10")),
        Map.of(o.skuId(), candidate(o, ProductStatus.ACTIVE, SellerStatus.APPROVED)));
    assertFalse(r.overdueAt(NOW.plus(TTL).minusSeconds(1)));
    assertTrue(r.overdueAt(NOW.plus(TTL)));
  }
}
