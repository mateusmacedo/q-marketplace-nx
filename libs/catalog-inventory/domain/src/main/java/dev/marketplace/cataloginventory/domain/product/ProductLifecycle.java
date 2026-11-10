package dev.marketplace.cataloginventory.domain.product;

import java.time.Instant;

/**
 * Situação gravada do produto, que a reconstrução preserva: o estado, a versão, as datas de criação e da última
 * mudança e a entrada na fila de moderação, nula fora de {@code PENDING_MODERATION}.
 */
public record ProductLifecycle(ProductStatus status, long version, Instant createdAt, Instant updatedAt,
    Instant moderationQueuedAt) {
}
