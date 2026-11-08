package dev.marketplace.cataloginventory.domain.offer;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Fato da oferta que outros contextos precisam conhecer; o repositório o grava
 * na transação do agregado.
 */
public sealed interface OfferEvent {
    UUID skuId();

    Instant occurredAt();

    /**
     * Nova definição de preço do SKU: preço base e promoções pendentes,
     * identificados por {@code priceVersion}.
     */
    record PriceChanged(UUID skuId, UUID productId, UUID storeId, Price price, List<Promotion> promotions,
            long priceVersion,
            Instant occurredAt) implements OfferEvent {
        /**
         * Exige {@code skuId} (nulo gera {@link NullPointerException}) e copia
         * {@code promotions} de forma imutável.
         */
        public PriceChanged {
            Objects.requireNonNull(skuId, "SKU obrigatório");
            promotions = List.copyOf(promotions);
        }
    }
}
