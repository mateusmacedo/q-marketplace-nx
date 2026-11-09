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

    /** Motivos prontos do bloqueio, com o rótulo da época, e complemento, como no histórico. */
    record ProductBlocked(UUID productId, UUID storeId, List<ReasonLabel> reasons, String note, Instant occurredAt)
            implements ProductEvent {
        public ProductBlocked {
            reasons = List.copyOf(reasons);
        }

        public List<String> reasonCodes() {
            return reasons.stream().map(ReasonLabel::code).toList();
        }

        /** Texto único derivado ({@link DecisionReason#compose}). */
        public String reason() {
            return DecisionReason.compose(reasons, note);
        }
    }

    /**
     * Transição do produto depois da criação; quem indexa a vitrine decide por ela
     * se mostra ou esconde o produto.
     */
    record ProductStatusChanged(UUID productId, UUID storeId, ProductTransition transition, ProductStatus from,
            ProductStatus to, List<ReasonLabel> reasons, String note, Instant occurredAt) implements ProductEvent {
        public ProductStatusChanged {
            reasons = List.copyOf(reasons);
        }

        public List<String> reasonCodes() {
            return reasons.stream().map(ReasonLabel::code).toList();
        }

        /** Texto único derivado ({@link DecisionReason#compose}); nulo fora da rejeição e do bloqueio. */
        public String reason() {
            return DecisionReason.compose(reasons, note);
        }
    }
}
