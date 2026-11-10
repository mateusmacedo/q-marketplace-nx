package dev.marketplace.cataloginventory.domain.inventory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Fato de estoque que outros contextos precisam conhecer. Cada saldo carrega a
 * versão da linha de estoque, que só
 * cresce: o consumidor descarta um saldo com versão menor que a já aplicada,
 * porque a ordem entre chaves não é garantida.
 */
public sealed interface InventoryEvent {
  Instant occurredAt();

  enum Cause {
    ADJUSTED, RELEASED, EXPIRED, COMMITTED
  }

  /** Saldo do SKU depois da mudança. */
  record SkuStock(UUID skuId, UUID productId, UUID storeId, int onHand, int reserved, long version) {
    /**
     * Copia o saldo de {@code level} e associa o produto e a loja do SKU.
     * Lança {@link NullPointerException} se {@code productId} ou
     * {@code storeId} for nulo.
     */
    public static SkuStock of(StockLevel level, UUID productId, UUID storeId) {
      return new SkuStock(level.skuId(), Objects.requireNonNull(productId, "produto obrigatório"),
          Objects.requireNonNull(storeId, "loja obrigatória"), level.onHand(), level.reserved(),
          level.version());
    }

    /** Disponível para reserva: físico vendável menos reservado. */
    public int available() {
      return onHand - reserved;
    }
  }

  record ReservedSku(SkuStock stock, int quantity) {
  }

  /**
   * {@code reservationId} identifica a reserva que liberou, expirou ou confirmou
   * o estoque; ausente em ajuste.
   */
  record InventoryUpdated(SkuStock stock, Cause cause, UUID reservationId, Instant occurredAt)
      implements
        InventoryEvent {
  }

  record InventoryReserved(UUID reservationId, String orderRef, List<ReservedSku> items, Instant expiresAt,
      Instant occurredAt)
      implements
        InventoryEvent {
    public InventoryReserved {
      items = List.copyOf(items);
    }
  }

  /** O disponível do SKU chegou a zero nesta mudança. */
  record InventoryDepleted(SkuStock stock, Instant occurredAt) implements InventoryEvent {
  }
}
