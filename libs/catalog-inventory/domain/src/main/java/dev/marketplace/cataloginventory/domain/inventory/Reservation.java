package dev.marketplace.cataloginventory.domain.inventory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Reserva de estoque com prazo de negócio; transições são aplicadas
 * atomicamente pelo armazenamento.
 */
public record Reservation(UUID id, UUID tenantId, String idempotencyKey, String requestHash, String orderRef,
    ReservationStatus status, List<ReservationItem> items, Instant createdAt, Instant expiresAt,
    Instant closedAt) {
  /**
   * Copia {@code items} de forma imutável e exige {@code id} e
   * {@code tenantId}. Lança {@link IllegalStateException} se
   * {@code expiresAt} não for posterior a {@code createdAt}.
   */
  public Reservation {
    Objects.requireNonNull(id, "id obrigatório");
    Objects.requireNonNull(tenantId, "tenant obrigatório");
    items = List.copyOf(items);
    if (!expiresAt.isAfter(createdAt))
      throw new IllegalStateException("Reserva expira antes de ser criada");
  }

  /**
   * Ativa, mas com prazo vencido: não pode mais ser confirmada, mesmo antes de o
   * varredor expirá-la.
   */
  public boolean overdueAt(Instant now) {
    return status == ReservationStatus.ACTIVE && !now.isBefore(expiresAt);
  }
}
