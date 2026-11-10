package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.inventory.Reservation;
import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Cada operação é uma transação atômica sobre a reserva e os saldos dos seus
 * SKUs.
 */
public interface ReservationRepository {
  /** Reserva do tenant com seus itens; vazio quando o id não existe nele. */
  Uni<Optional<Reservation>> find(UUID tenantId, UUID id);

  /**
   * Reserva criada com essa chave de idempotência no tenant; vazio quando a chave
   * nunca foi usada.
   */
  Uni<Optional<Reservation>> findByIdempotencyKey(UUID tenantId, String idempotencyKey);

  /**
   * Grava a reserva e soma o reservado de todos os itens, ou nada. Falha com
   * recusa {@code insufficient-stock}
   * quando algum SKU não tem saldo, ou com {@link DuplicateException} quando a
   * chave de idempotência já existe.
   */
  Uni<Void> insert(Reservation reservation);

  /**
   * ACTIVE para RELEASED, devolvendo o reservado; vazio quando a reserva não
   * estava ativa.
   */
  Uni<Optional<Reservation>> release(UUID tenantId, UUID id, Instant at);

  /**
   * ACTIVE e dentro do prazo para COMMITTED, baixando físico e reservado; vazio
   * quando não se aplica.
   */
  Uni<Optional<Reservation>> commit(UUID tenantId, UUID id, Instant at);

  /** Novo prazo para reserva ativa e não vencida; vazio quando não se aplica. */
  Uni<Optional<Reservation>> extend(UUID tenantId, UUID id, Instant newExpiry, Instant at);

  /**
   * Expira até {@code batch} reservas vencidas, devolvendo o estoque; retorna
   * quantas expiraram.
   */
  Uni<Integer> expireDue(Instant at, int batch);
}
