package dev.marketplace.cataloginventory.domain.inventory;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class StockLevelTest {
  @Test
  void availableIsOnHandMinusReserved() {
    assertEquals(3, new StockLevel(UUID.randomUUID(), 10, 7, 4, 1).available());
  }

  @Test
  void neverNegativeNorReservedAboveOnHand() {
    assertThrows(IllegalStateException.class, () -> new StockLevel(UUID.randomUUID(), 1, 2, 0, 0));
    assertThrows(IllegalStateException.class, () -> new StockLevel(UUID.randomUUID(), -1, 0, 0, 0));
  }

  @Test
  void validatesAdjustmentRange() {
    assertEquals(0, StockLevel.requireOnHand(0));
    assertThrows(DomainRuleViolation.class, () -> StockLevel.requireOnHand(-1));
    assertThrows(DomainRuleViolation.class, () -> StockLevel.requireOnHand(StockLevel.MAX_ON_HAND + 1));
  }
}
