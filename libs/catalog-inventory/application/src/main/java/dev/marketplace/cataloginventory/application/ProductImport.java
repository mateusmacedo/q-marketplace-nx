package dev.marketplace.cataloginventory.application;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Lote de importação de produtos enviado pelo seller e processado em segundo
 * plano. {@code actor} é o subject de quem enviou; {@code startedAt} marca a
 * última reivindicação ou progresso, e {@code finishedAt}, a conclusão.
 */
public record ProductImport(UUID id, UUID tenantId, UUID storeId, String actor, ImportStatus status, int totalLines,
    Instant createdAt, Instant startedAt, Instant finishedAt) {
  public enum ImportStatus {
    PENDING, PROCESSING, COMPLETED
  }

  /** Lote recém-enviado, aguardando o processador. */
  public static ProductImport pending(UUID id, UUID tenantId, UUID storeId, String actor, int totalLines,
      Instant now) {
    return new ProductImport(id, tenantId, storeId, actor, ImportStatus.PENDING, totalLines, now, null, null);
  }

  /**
   * Reconstrói o seller que enviou o lote, com a loja dele e só
   * {@code MANAGE_OWN_PRODUCTS}, para os casos de uso aplicarem as mesmas
   * regras da API.
   */
  public Actor seller() {
    return new Actor(tenantId, Optional.of(storeId), actor, Set.of(Capability.MANAGE_OWN_PRODUCTS));
  }
}
