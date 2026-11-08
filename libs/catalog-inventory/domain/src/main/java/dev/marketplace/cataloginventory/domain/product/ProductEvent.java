package dev.marketplace.cataloginventory.domain.product;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Fato do produto que outros contextos precisam conhecer; o repositório o grava
 * na transação do agregado.
 */
public sealed interface ProductEvent {
    UUID productId();

    UUID storeId();

    Instant occurredAt();

    record SkuSummary(UUID skuId, String sellerSku) {
    }

    record ProductCreated(UUID productId, UUID storeId, UUID categoryId, String currency, String title,
            List<SkuSummary> skus, Instant occurredAt) implements ProductEvent {
        /**
         * Exige {@code productId} (nulo gera {@link NullPointerException}) e
         * copia {@code skus} de forma imutável.
         */
        public ProductCreated {
            Objects.requireNonNull(productId, "produto obrigatório");
            skus = List.copyOf(skus);
        }
    }

    record ProductBlocked(UUID productId, UUID storeId, String reason, Instant occurredAt) implements ProductEvent {
    }

    /**
     * Transição do produto depois da criação; quem indexa a vitrine decide por ela
     * se mostra ou esconde o produto.
     */
    record ProductStatusChanged(UUID productId, UUID storeId, ProductTransition transition, ProductStatus from,
            ProductStatus to,
            String reason, Instant occurredAt) implements ProductEvent {
    }
}
