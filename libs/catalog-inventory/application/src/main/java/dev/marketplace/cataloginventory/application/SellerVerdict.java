package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Decisão do contexto KYB/KYC sobre a loja, recebida por evento;
 * {@code eventId} identifica a entrega para deduplicação.
 */
public record SellerVerdict(UUID eventId, UUID tenantId, UUID storeId, SellerStatus status, Instant decidedAt) {
    /** Exige todos os campos; campo nulo lança {@link NullPointerException}. */
    public SellerVerdict {
        Objects.requireNonNull(eventId, "evento obrigatório");
        Objects.requireNonNull(tenantId, "tenant obrigatório");
        Objects.requireNonNull(storeId, "loja obrigatória");
        Objects.requireNonNull(status, "situação obrigatória");
        Objects.requireNonNull(decidedAt, "instante da decisão obrigatório");
    }

    /**
     * Resultado da aplicação: duplicado já foi processado; obsoleto é mais antigo
     * que a decisão já registrada.
     */
    public enum Outcome {
        APPLIED, DUPLICATE, STALE
    }
}
