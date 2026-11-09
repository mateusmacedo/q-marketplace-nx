package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.application.CatalogStatsService.ActivityPoint;
import dev.marketplace.cataloginventory.application.CatalogStatsService.Indicator;
import dev.marketplace.cataloginventory.application.CatalogStatsService.MonthValue;
import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static dev.marketplace.cataloginventory.application.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogStatsServiceTest {
    final Catalog catalog = new Catalog();
    final CatalogStatsService stats = catalog.stats;

    void at(String instant) {
        catalog.clock.advance(Duration.between(catalog.clock.instant(), Instant.parse(instant)));
    }

    static List<Long> values(Indicator indicator) {
        return indicator.months().stream().map(m -> m.value().orElse(null)).toList();
    }

    /**
     * Linha do tempo da loja: dois produtos aprovados e um rascunho em novembro, o rascunho enviado às 23h de 30/11
     * em São Paulo (já dezembro em UTC), rejeitado em dezembro junto com um bloqueio, e em janeiro um rascunho novo,
     * um aprovado sem saldo e saldo para o primeiro aprovado.
     */
    void timeline() {
        Product first = catalog.active(sku("A-P", "P"));
        Product blocked = catalog.active(sku("C-P", "P"));
        Product draft = catalog.offered(catalog.draft(sku("B-P", "P")));
        at("2026-12-01T02:00:00Z");
        item(catalog.products.submit(catalog.seller, draft.id(), Optional.empty()));
        at("2026-12-10T12:00:00Z");
        item(catalog.moderation.reject(moderator(), draft.id(), Optional.empty(), List.of("wrong-category"),
                Optional.empty()));
        item(catalog.moderation.block(moderator(), blocked.id(), Optional.empty(), List.of("prohibited-item"),
                Optional.empty()));
        at("2027-01-05T12:00:00Z");
        item(catalog.inventory.adjust(catalog.seller, first.skus().getFirst().id(), Optional.empty(), 5));
        catalog.draft(sku("D-P", "P"));
        catalog.active(sku("E-P", "P"));
    }

    @Test
    void storeStatsRebuildMonthEndsFromHistoryInSaoPauloTime() {
        timeline();
        var store = item(stats.store(catalog.seller, Optional.empty(), Optional.empty(), Optional.empty()));
        assertEquals(catalog.storeId, store.storeId());
        assertEquals(1, store.counts().get(ProductStatus.ACTIVE));
        assertEquals(1, store.counts().get(ProductStatus.OUT_OF_STOCK));
        assertEquals(2, store.counts().get(ProductStatus.DRAFT));
        assertEquals(1, store.counts().get(ProductStatus.BLOCKED));
        assertEquals(0, store.counts().get(ProductStatus.ARCHIVED));
        assertEquals(5, store.total());

        assertEquals(LocalDate.parse("2026-07-01"), store.active().months().getFirst().month());
        assertEquals(LocalDate.parse("2027-01-01"), store.active().months().getLast().month());
        assertEquals(1, store.active().value());
        assertEquals(Arrays.asList(0L, 0L, 0L, 0L, 2L, 1L, 2L), values(store.active()));
        assertTrue(store.active().previous().isEmpty());
        assertTrue(store.active().change().isEmpty());
        assertEquals(Arrays.asList(0L, 0L, 0L, 0L, 0L, 1L, 2L), values(store.draft()));
        assertEquals(Optional.of(1L), store.draft().change());
        assertEquals(Arrays.asList(0L, 0L, 0L, 0L, 0L, 1L, 1L), values(store.blocked()));
        assertEquals(1, store.outOfStock().value());
        assertTrue(store.outOfStock().previous().isEmpty());
        assertEquals(Arrays.asList(null, null, null, null, null, null, 1L), values(store.outOfStock()));

        var activity = item(stats.store(catalog.seller, Optional.of("month"), Optional.of(LocalDate.parse("2026-11-20")),
                Optional.of(LocalDate.parse("2027-01-02"))));
        assertEquals(LocalDate.parse("2026-11-01"), activity.activity().from());
        assertEquals(LocalDate.parse("2027-01-31"), activity.activity().to());
        assertEquals(List.of(new ActivityPoint(LocalDate.parse("2026-11-01"), 3, 2, 0, 0),
                new ActivityPoint(LocalDate.parse("2026-12-01"), 0, 0, 1, 1),
                new ActivityPoint(LocalDate.parse("2027-01-01"), 1, 1, 0, 0)), activity.activity().points());
    }

    @Test
    void moderationStatsCountQueueAndDecisionsOfTheTenant() {
        timeline();
        var moderation = item(stats.moderation(moderator(), Optional.of("day"), Optional.empty(), Optional.empty()));
        assertEquals(Arrays.asList(0L, 0L, 0L, 0L, 1L, 0L, 0L), values(moderation.queue()));
        assertEquals(0, moderation.queue().value());
        assertEquals(Arrays.asList(0L, 0L, 0L, 0L, 2L, 0L, 1L), values(moderation.approved()));
        assertEquals(Optional.of(1L), moderation.approved().change());
        assertEquals(0, moderation.rejected().value());
        assertEquals(Optional.of(-1L), moderation.rejected().change());
        assertEquals(1, moderation.blocked().previous().orElseThrow());
        assertEquals(30, moderation.activity().points().size());
        assertEquals(LocalDate.parse("2026-12-07"), moderation.activity().from());
        assertEquals(LocalDate.parse("2027-01-05"), moderation.activity().to());
        ActivityPoint today = moderation.activity().points().getLast();
        assertEquals(1, today.approved());
        assertEquals(1, today.submitted());
    }

    @Test
    void moderationQueueCountsOnlyThePendingProductsOfTheTenant() {
        Product pending = catalog.offered(catalog.draft(sku("A-P", "P")));
        item(catalog.products.submit(catalog.seller, pending.id(), Optional.empty()));
        catalog.draft(sku("B-P", "P"));
        assertEquals(1L, item(stats.moderationQueue(moderator())));
        assertEquals(item(stats.moderation(moderator(), Optional.empty(), Optional.empty(), Optional.empty()))
                .queue().value(), item(stats.moderationQueue(moderator())));
        Actor otherTenant = new Actor(UUID.randomUUID(), Optional.empty(), "x", Set.of(Capability.MODERATE_PRODUCTS));
        assertEquals(0L, item(stats.moderationQueue(otherTenant)));
        failure(stats.moderationQueue(catalog.seller), AccessDeniedException.class);
    }

    @Test
    void defaultPeriodsAndValidation() {
        at("2027-01-05T12:00:00Z");
        var month = item(stats.store(catalog.seller, Optional.empty(), Optional.empty(), Optional.empty())).activity();
        assertEquals(StatsGranularity.MONTH, month.granularity());
        assertEquals(12, month.points().size());
        assertEquals(LocalDate.parse("2027-01-01"), month.from());
        assertEquals(LocalDate.parse("2027-12-31"), month.to());
        var year = item(stats.store(catalog.seller, Optional.of("year"), Optional.empty(), Optional.empty()))
                .activity();
        assertEquals(List.of(2023, 2024, 2025, 2026, 2027),
                year.points().stream().map(p -> p.period().getYear()).toList());

        Optional<LocalDate> from = Optional.of(LocalDate.parse("2026-01-01"));
        failure(stats.store(catalog.seller, Optional.of("week"), Optional.empty(), Optional.empty()),
                InvalidInputException.class);
        failure(stats.store(catalog.seller, Optional.empty(), from, Optional.empty()), InvalidInputException.class);
        failure(stats.store(catalog.seller, Optional.empty(), from, Optional.of(LocalDate.parse("2025-12-31"))),
                InvalidInputException.class);
        failure(stats.store(catalog.seller, Optional.of("day"), from, Optional.of(LocalDate.parse("2027-01-02"))),
                InvalidInputException.class);
        assertEquals(366, item(stats.store(catalog.seller, Optional.of("day"), from,
                Optional.of(LocalDate.parse("2027-01-01")))).activity().points().size());
    }

    @Test
    void datesOutsideFourDigitYearsAreRefused() {
        for (String date : List.of("+999999999-12-31", "+294276-01-01", "+10000-01-01", "-5000-01-01", "0000-12-31"))
            for (String granularity : List.of("day", "year")) {
                Optional<LocalDate> day = Optional.of(LocalDate.parse(date));
                failure(stats.store(catalog.seller, Optional.of(granularity), day, day), InvalidInputException.class);
                failure(stats.moderation(moderator(), Optional.of(granularity), day, day),
                        InvalidInputException.class);
            }
        Optional<LocalDate> last = Optional.of(LocalDate.parse("9999-12-31"));
        assertEquals(LocalDate.parse("9999-12-31"),
                item(stats.store(catalog.seller, Optional.of("day"), last, last)).activity().to());
        Optional<LocalDate> first = Optional.of(LocalDate.parse("0001-01-01"));
        assertEquals(LocalDate.parse("0001-01-01"),
                item(stats.moderation(moderator(), Optional.of("year"), first, first)).activity().from());
    }

    @Test
    void activeWithoutStockAndWithoutTransitionsShowsNoChange() {
        catalog.active(sku("A-P", "P"));
        at("2027-01-05T12:00:00Z");
        var store = item(stats.store(catalog.seller, Optional.empty(), Optional.empty(), Optional.empty()));
        assertEquals(0, store.active().value());
        assertEquals(Arrays.asList(0L, 0L, 0L, 0L, 1L, 1L, 1L), values(store.active()));
        assertTrue(store.active().previous().isEmpty());
        assertTrue(store.active().change().isEmpty());
        assertEquals(1, store.outOfStock().value());
    }

    @Test
    void eachRoleReadsItsOwnNumbers() {
        failure(stats.store(moderator(), Optional.empty(), Optional.empty(), Optional.empty()),
                AccessDeniedException.class);
        failure(stats.moderation(catalog.seller, Optional.empty(), Optional.empty(), Optional.empty()),
                AccessDeniedException.class);
        failure(stats.categories(catalog.seller), AccessDeniedException.class);
        catalog.draft(sku("A-P", "P"));
        item(catalog.categories.create(admin(), Optional.of(catalog.category), "regatas", "Regatas", List.of()));
        var home = item(catalog.categories.create(admin(), Optional.empty(), "casa", "Casa", List.of()));
        item(catalog.categories.revise(admin(), home.category().id(), Optional.empty(), "Casa", List.of(), false));
        assertEquals(new CatalogStatsRepository.CategoryCounts(3, 2, 1, 1), item(stats.categories(admin())));
    }

    @Test
    void storeWithoutProductsHasZerosAndNoStockHistory() {
        var empty = item(stats.store(catalog.seller, Optional.empty(), Optional.empty(), Optional.empty()));
        assertEquals(new MonthValue(LocalDate.parse("2026-11-01"), Optional.of(0L)),
                empty.outOfStock().months().getLast());
        assertEquals(new MonthValue(LocalDate.parse("2026-10-01"), Optional.empty()),
                empty.outOfStock().months().get(5));
        assertEquals(Arrays.asList(0L, 0L, 0L, 0L, 0L, 0L, 0L), values(empty.draft()));
        assertEquals(0, empty.total());
    }
}
