package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.ReasonCode;

/**
 * Registro dos códigos de recusa que os casos de uso emitem além dos do
 * domínio. Código novo nasce aqui e ganha texto no catálogo de mensagens do
 * adaptador HTTP.
 */
public enum ApplicationReasons implements ReasonCode {
    INVALID_ROW,
    IDEMPOTENCY_KEY_REUSED,
    RESERVATION_EXPIRED,
    RESERVATION_NOT_ACTIVE,
    RESERVATION_LIFETIME_EXCEEDED,
    MODERATION_REASON_LIMIT,
    MODERATION_REASON_CATALOG_LIMIT
}
