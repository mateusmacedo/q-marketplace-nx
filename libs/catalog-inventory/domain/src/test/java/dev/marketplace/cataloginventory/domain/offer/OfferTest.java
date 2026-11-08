package dev.marketplace.cataloginventory.domain.offer;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.Money;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OfferTest {
    static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    static Price price(String list, String sale) {
        return new Price(list == null ? null : Money.of(list, "BRL"), Money.of(sale, "BRL"));
    }

    static Offer offer() {
        return Offer.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "BRL",
                price("120", "100"), NOW);
    }

    static Promotion promo(String amount, Duration startIn, Duration length) {
        Instant start = NOW.plus(startIn);
        return new Promotion(UUID.randomUUID(), Money.of(amount, "BRL"), start, start.plus(length));
    }

    @Test
    void creationStartsAtPriceVersionOneAndRecordsThePrice() {
        Offer offer = offer();
        assertEquals(1, offer.priceVersion());
        var created = (OfferEvent.PriceChanged) offer.drainEvents().get(0);
        assertEquals(1, created.priceVersion());
        assertEquals(Money.of("100", "BRL"), created.price().salePrice());
        assertTrue(offer.drainEvents().isEmpty(), "drenar esvazia os eventos pendentes");
    }

    @Test
    void listPriceCannotBeBelowSalePrice() {
        assertEquals("list-price-below-sale-price",
                assertThrows(DomainRuleViolation.class, () -> price("90", "100")).code());
    }

    @Test
    void currencyIsTheProductsAndCannotChange() {
        Offer offer = offer();
        Price dollars = new Price(null, Money.of("10", "USD"));
        assertEquals("currency-mismatch",
                assertThrows(DomainRuleViolation.class, () -> offer.changePrice(dollars, NOW)).code());
        assertThrows(DomainRuleViolation.class,
                () -> Offer.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), "BRL", dollars, NOW));
    }

    @Test
    void priceChangeIncrementsVersionAndRecordsEvent() {
        Offer offer = offer();
        offer.drainEvents();
        offer.changePrice(price("130", "110"), NOW);
        assertEquals(2, offer.priceVersion());
        assertEquals(Money.of("110", "BRL"), offer.effectivePrice(NOW).salePrice());
        assertEquals(2, ((OfferEvent.PriceChanged) offer.drainEvents().get(0)).priceVersion());
    }

    @Test
    void promotionAppliesOnlyInsideItsWindow() {
        Offer offer = offer();
        Promotion blackFriday = promo("80", Duration.ofHours(1), Duration.ofDays(1));
        offer.schedulePromotion(blackFriday, NOW);
        assertEquals(Money.of("100", "BRL"), offer.effectivePrice(NOW).salePrice());
        EffectivePrice during = offer.effectivePrice(NOW.plus(Duration.ofHours(2)));
        assertEquals(Money.of("80", "BRL"), during.salePrice());
        assertEquals(blackFriday.id(), during.promotionId().orElseThrow());
        assertEquals(Money.of("120", "BRL"), during.listPrice());
        assertEquals(Money.of("100", "BRL"), offer.effectivePrice(blackFriday.endsAt()).salePrice());
        assertEquals(2, during.priceVersion());
    }

    @Test
    void rejectsOverlappingPromotions() {
        Offer offer = offer();
        offer.schedulePromotion(promo("80", Duration.ofHours(1), Duration.ofDays(2)), NOW);
        var error = assertThrows(DomainRuleViolation.class,
                () -> offer.schedulePromotion(promo("70", Duration.ofDays(1), Duration.ofDays(1)), NOW));
        assertEquals("promotion-overlap", error.code());
    }

    @Test
    void promotionCannotExceedListPriceNorEndInThePast() {
        Offer offer = offer();
        assertEquals("promotion-above-list-price", assertThrows(DomainRuleViolation.class,
                () -> offer.schedulePromotion(promo("150", Duration.ofHours(1), Duration.ofDays(1)), NOW)).code());
        assertThrows(DomainRuleViolation.class,
                () -> offer.schedulePromotion(promo("80", Duration.ofDays(-3), Duration.ofDays(1)), NOW));
    }

    @Test
    void lowerListPriceMustKeepPendingPromotionsValid() {
        Offer offer = offer();
        offer.schedulePromotion(promo("110", Duration.ofHours(1), Duration.ofDays(1)), NOW);
        assertThrows(DomainRuleViolation.class, () -> offer.changePrice(price("105", "100"), NOW));
    }

    @Test
    void cancelsPendingPromotionButNotEndedOne() {
        Offer offer = offer();
        Promotion p = promo("80", Duration.ofHours(1), Duration.ofDays(1));
        offer.schedulePromotion(p, NOW);
        assertEquals("promotion-ended",
                assertThrows(DomainRuleViolation.class, () -> offer.cancelPromotion(p.id(), p.endsAt())).code());
        offer.cancelPromotion(p.id(), NOW);
        assertTrue(offer.promotions().isEmpty());
        assertEquals(3, offer.priceVersion());
        assertEquals(List.of(1L, 2L, 3L), offer.drainEvents().stream()
                .map(e -> ((OfferEvent.PriceChanged) e).priceVersion()).toList());
    }
}
