package dev.marketplace.cataloginventory.domain.inventory;

import dev.marketplace.cataloginventory.domain.Money;
import dev.marketplace.cataloginventory.domain.inventory.InventoryEvent.Cause;
import dev.marketplace.cataloginventory.domain.inventory.InventoryEvent.InventoryDepleted;
import dev.marketplace.cataloginventory.domain.inventory.InventoryEvent.InventoryReserved;
import dev.marketplace.cataloginventory.domain.inventory.InventoryEvent.InventoryUpdated;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class InventoryEventsTest {
        static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
        final UUID product = UUID.randomUUID();
        final UUID store = UUID.randomUUID();
        final UUID skuA = UUID.randomUUID();
        final UUID skuB = UUID.randomUUID();

        Reservation reservation(ReservationStatus status) {
                List<ReservationItem> items = List.of(
                                new ReservationItem(skuA, product, store, 2, Money.of("10", "BRL"), 1),
                                new ReservationItem(skuB, product, store, 1, Money.of("20", "BRL"), 1));
                return new Reservation(UUID.randomUUID(), UUID.randomUUID(), "k-1", "h", "pedido-1", status, items, NOW,
                                NOW.plusSeconds(900), null);
        }

        @Test
        void adjustmentReportsNewStockAndDepletionOnlyWhenAvailableReachesZero() {
                List<InventoryEvent> events = InventoryEvents.adjusted(new StockLevel(skuA, 5, 2, 0, 3),
                                new StockLevel(skuA, 2, 2, 0, 4),
                                product, store, NOW);
                var updated = (InventoryUpdated) events.get(0);
                assertEquals(Cause.ADJUSTED, updated.cause());
                assertEquals(0, updated.stock().available());
                assertEquals(4, updated.stock().version());
                assertNull(updated.reservationId());
                assertInstanceOf(InventoryDepleted.class, events.get(1));

                assertEquals(1,
                                InventoryEvents.adjusted(new StockLevel(skuA, 0, 0, 0, 1),
                                                new StockLevel(skuA, 0, 0, 0, 2), product,
                                                store, NOW).size(),
                                "já esgotado não esgota de novo");
        }

        @Test
        void reservationReportsItemsWithStockAfterAndDepletedSkus() {
                Reservation r = reservation(ReservationStatus.ACTIVE);
                List<InventoryEvent> events = InventoryEvents.reserved(r, Map.of(skuA, new StockLevel(skuA, 5, 2, 0, 2),
                                skuB, new StockLevel(skuB, 1, 1, 0, 7)));
                var reserved = (InventoryReserved) events.get(0);
                assertEquals(r.id(), reserved.reservationId());
                assertEquals(List.of(2, 1),
                                reserved.items().stream().map(InventoryEvent.ReservedSku::quantity).toList());
                assertEquals(3, reserved.items().get(0).stock().available());
                assertEquals(skuB, ((InventoryDepleted) events.get(1)).stock().skuId());
                assertEquals(2, events.size());
        }

        @Test
        void closingReportsStockOfEveryItemWithTheCause() {
                Reservation r = reservation(ReservationStatus.EXPIRED);
                Map<UUID, StockLevel> after = Map.of(skuA, new StockLevel(skuA, 5, 0, 0, 3), skuB,
                                new StockLevel(skuB, 1, 0, 0, 8));
                List<InventoryEvent> events = InventoryEvents.closed(r, ReservationStatus.EXPIRED, after, NOW);
                assertEquals(2, events.size());
                events.forEach(e -> {
                        assertEquals(Cause.EXPIRED, ((InventoryUpdated) e).cause());
                        assertEquals(r.id(), ((InventoryUpdated) e).reservationId());
                });
                assertThrows(IllegalArgumentException.class,
                                () -> InventoryEvents.closed(r, ReservationStatus.ACTIVE, after, NOW));
                assertThrows(IllegalStateException.class,
                                () -> InventoryEvents.closed(r, ReservationStatus.RELEASED, Map.of(), NOW));
        }
}
