package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.InvalidTransitionException;
import dev.marketplace.cataloginventory.domain.Money;
import dev.marketplace.cataloginventory.domain.inventory.RequestedItem;
import dev.marketplace.cataloginventory.domain.inventory.StockConflictException;
import dev.marketplace.cataloginventory.domain.inventory.StockLevel;
import dev.marketplace.cataloginventory.domain.product.Product;
import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.marketplace.cataloginventory.application.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryServiceTest {
  final Catalog catalog = new Catalog();
  final InventoryService inventory = catalog.inventory;
  final Actor seller = catalog.seller;
  final UUID sku;

  InventoryServiceTest() {
    Product product = catalog.active(sku("CAM-P", "P"));
    sku = product.skus().get(0).id();
    item(inventory.adjust(seller, sku, Optional.empty(), 2));
  }

  @Test
  void adjustmentChecksReservedStockVersionAndOwnership() {
    var request = new ReservationService.Request("k1", "pedido-1", Optional.empty(),
        List.of(new RequestedItem(sku, 2, Money.of("50", "BRL"))));
    item(catalog.reservations.reserve(checkout(), request));
    var below = failure(inventory.adjust(seller, sku, Optional.empty(), 1), StockConflictException.class);
    assertEquals("stock-below-reserved", below.code());
    StockLevel current = catalog.store.stock.get(sku);
    failure(inventory.adjust(seller, sku, Optional.of(current.version() + 5), 9), VersionMismatchException.class);
    assertEquals(9, item(inventory.adjust(seller, sku, Optional.of(current.version()), 9)).onHand());
    failure(inventory.adjust(seller(UUID.randomUUID()), sku, Optional.empty(), 1), NotFoundException.class);
    failure(inventory.adjust(seller, UUID.randomUUID(), Optional.empty(), 1), NotFoundException.class);
    assertEquals(9, item(inventory.get(moderator(), sku)).onHand());
    assertTrue(catalog.store.adjustments.contains("seller-" + catalog.storeId + ":2->9"));
  }

  @Test
  void archivedProductFreezesItsStock() {
    Product product = catalog.offered(catalog.draft(sku("BON-P", "P")));
    item(catalog.products.archive(seller, product.id(), Optional.empty()));
    failure(inventory.adjust(seller, product.skus().get(0).id(), Optional.empty(), 1),
        InvalidTransitionException.class);
  }
}
