package dev.marketplace.cataloginventory.domain.offer;

import dev.marketplace.cataloginventory.domain.Money;
import java.util.Optional;
import java.util.UUID;

/**
 * Preço vigente num instante; {@code priceVersion} identifica a definição de
 * preço usada (snapshot do checkout).
 */
public record EffectivePrice(Money listPrice, Money salePrice, Optional<UUID> promotionId, long priceVersion) {
}
