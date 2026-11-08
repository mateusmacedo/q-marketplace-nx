package dev.marketplace.cataloginventory.domain.product;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro imutável de uma transição do produto, para auditoria e backoffice.
 */
public record StatusChange(UUID id, ProductTransition transition, ProductStatus from, ProductStatus to, String actor,
        String reason, Instant at) {
}
