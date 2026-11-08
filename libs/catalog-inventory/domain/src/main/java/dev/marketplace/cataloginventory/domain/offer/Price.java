package dev.marketplace.cataloginventory.domain.offer;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.Money;
import static dev.marketplace.cataloginventory.domain.DomainRuleViolation.invalid;

/**
 * Preço "de/por": {@code listPrice} (de, opcional) nunca é menor que
 * {@code salePrice} (por).
 */
public record Price(Money listPrice, Money salePrice) {
    /**
     * Valida o par de preços; {@code listPrice} é opcional. Recusa com
     * {@code invalid-value} se faltar {@code salePrice} ou as moedas diferirem,
     * e com {@code list-price-below-sale-price} se o preço "de" for menor que o
     * "por".
     */
    public Price {
        if (salePrice == null)
            throw invalid("Preço de venda é obrigatório");
        if (listPrice != null && listPrice.compareAmount(salePrice) < 0)
            throw new DomainRuleViolation("list-price-below-sale-price",
                    "Preço \"de\" não pode ser menor que o preço \"por\"");
    }

    public String currency() {
        return salePrice.currency();
    }
}
