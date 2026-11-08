package dev.marketplace.cataloginventory.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento já serializado no envelope do contrato, pronto para entrar na outbox e
 * depois no tópico.
 */
public record OutboxMessage(UUID id, UUID tenantId, String topic, String key, String eventType, String payload,
        Instant createdAt,
        String traceParent) {
}
