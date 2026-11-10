package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.domain.inventory.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contratos HTTP de reservas de estoque, consumidos pelo checkout e pelo OMS.
 */
public final class ReservationModels {
  private ReservationModels() {
  }

  public record ItemRequest(@NotNull UUID skuId, @NotNull @Min(1) @Max(RequestedItem.MAX_QUANTITY) Integer quantity,
      @Schema(
          description = "Preço unitário aceito no checkout; divergência recusa com price-changed") @NotNull @Valid MoneyDto expectedUnitPrice) {
    RequestedItem toDomain() {
      return new RequestedItem(skuId, quantity, expectedUnitPrice.toDomain());
    }
  }

  public record CreateReservationRequest(
      @Size(max = ReservationPolicy.ORDER_REF_MAX_LENGTH) @Schema(description = "Referência do pedido no OMS",
          nullable = true) String orderRef,
      @Positive @Schema(description = "Prazo em segundos; ausente usa o padrão do serviço",
          nullable = true) Long ttlSeconds,
      @NotEmpty @Size(max = ReservationPolicy.MAX_ITEMS) List<@Valid @NotNull ItemRequest> items) {
  }

  public record ExtendReservationRequest(@NotNull @Positive Long ttlSeconds) {
  }

  public record ReservationItemResponse(UUID skuId, UUID productId, UUID storeId, int quantity, MoneyDto unitPrice,
      long priceVersion) {
    static ReservationItemResponse of(ReservationItem i) {
      return new ReservationItemResponse(i.skuId(), i.productId(), i.storeId(), i.quantity(),
          MoneyDto.of(i.unitPrice()), i.priceVersion());
    }
  }

  public record ReservationResponse(UUID id, String orderRef, ReservationStatus status, Instant createdAt,
      Instant expiresAt,
      Instant closedAt, List<ReservationItemResponse> items) {
    static ReservationResponse of(Reservation r) {
      return new ReservationResponse(r.id(), r.orderRef(), r.status(), r.createdAt(), r.expiresAt(), r.closedAt(),
          r.items().stream().map(ReservationItemResponse::of).toList());
    }
  }
}
