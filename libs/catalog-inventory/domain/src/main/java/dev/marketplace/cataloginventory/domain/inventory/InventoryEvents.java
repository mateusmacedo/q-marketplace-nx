package dev.marketplace.cataloginventory.domain.inventory;

import dev.marketplace.cataloginventory.domain.inventory.InventoryEvent.Cause;
import dev.marketplace.cataloginventory.domain.inventory.InventoryEvent.InventoryDepleted;
import dev.marketplace.cataloginventory.domain.inventory.InventoryEvent.InventoryReserved;
import dev.marketplace.cataloginventory.domain.inventory.InventoryEvent.InventoryUpdated;
import dev.marketplace.cataloginventory.domain.inventory.InventoryEvent.ReservedSku;
import dev.marketplace.cataloginventory.domain.inventory.InventoryEvent.SkuStock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deriva os eventos de estoque do saldo antes e depois de cada operação. O
 * armazenamento aplica as operações de forma
 * atômica e chama estas regras dentro da mesma transação, para gravar os
 * eventos junto com o novo saldo.
 */
public final class InventoryEvents {
  private InventoryEvents() {
  }

  /**
   * Gera {@code InventoryUpdated} com causa {@code ADJUSTED}, sem reserva
   * associada, e acrescenta {@code InventoryDepleted} se o disponível passou
   * de positivo a zero.
   */
  public static List<InventoryEvent> adjusted(StockLevel before, StockLevel after, UUID productId, UUID storeId,
      Instant at) {
    SkuStock stock = SkuStock.of(after, productId, storeId);
    List<InventoryEvent> events = new ArrayList<>();
    events.add(new InventoryUpdated(stock, Cause.ADJUSTED, null, at));
    if (before.available() > 0 && after.available() == 0)
      events.add(new InventoryDepleted(stock, at));
    return events;
  }

  /**
   * Reserva gravada; todo item reservado tinha saldo, então disponível zero
   * depois dela é esgotamento novo.
   */
  public static List<InventoryEvent> reserved(Reservation reservation, Map<UUID, StockLevel> after) {
    List<ReservedSku> items = reservation.items().stream()
        .map(item -> new ReservedSku(stockOf(after, item), item.quantity())).toList();
    List<InventoryEvent> events = new ArrayList<>();
    events.add(new InventoryReserved(reservation.id(), reservation.orderRef(), items, reservation.expiresAt(),
        reservation.createdAt()));
    items.stream().filter(item -> item.stock().available() == 0)
        .forEach(item -> events.add(new InventoryDepleted(item.stock(), reservation.createdAt())));
    return events;
  }

  /**
   * Reserva liberada, expirada ou confirmada: o novo saldo de cada SKU que ela
   * retinha.
   */
  public static List<InventoryEvent> closed(Reservation reservation, ReservationStatus outcome,
      Map<UUID, StockLevel> after,
      Instant at) {
    Cause cause = switch (outcome) {
      case RELEASED -> Cause.RELEASED;
      case EXPIRED -> Cause.EXPIRED;
      case COMMITTED -> Cause.COMMITTED;
      case ACTIVE -> throw new IllegalArgumentException("Reserva ativa não encerra estoque");
    };
    return reservation.items().stream()
        .<InventoryEvent>map(item -> new InventoryUpdated(stockOf(after, item), cause, reservation.id(), at))
        .toList();
  }

  private static SkuStock stockOf(Map<UUID, StockLevel> after, ReservationItem item) {
    StockLevel level = after.get(item.skuId());
    if (level == null)
      throw new IllegalStateException("Saldo ausente para o SKU " + item.skuId());
    return SkuStock.of(level, item.productId(), item.storeId());
  }
}
