package dev.marketplace.cataloginventory.domain;

import java.math.BigDecimal;
import java.util.regex.Pattern;
import static dev.marketplace.cataloginventory.domain.DomainRuleViolation.invalid;

/**
 * Valor monetário exato em centavos; o valor é normalizado para duas casas
 * decimais.
 */
public record Money(BigDecimal amount, String currency) {
    /**
     * INVARIANT: limite de numeric(19,2) no armazenamento; acima disso o valor
     * seria truncado.
     */
    public static final BigDecimal MAX = new BigDecimal("99999999999999999.99");
    /**
     * Formato ISO 4217 da moeda (três letras maiúsculas), reusado nas validações
     * de borda.
     */
    public static final String CURRENCY_PATTERN = "[A-Z]{3}";
    private static final Pattern CURRENCY = Pattern.compile(CURRENCY_PATTERN);

    /**
     * Normaliza o valor para duas casas; zeros à direita não contam na
     * validação. Recusa com {@code invalid-value} valor nulo, negativo, com
     * mais de duas casas significativas ou acima de {@link #MAX}, e moeda fora
     * da ISO 4217.
     */
    public Money {
        if (amount == null)
            throw invalid("Valor monetário obrigatório");
        requireCurrency(currency);
        if (amount.signum() < 0)
            throw invalid("Valor monetário não pode ser negativo");
        if (amount.stripTrailingZeros().scale() > 2)
            throw invalid("Valor monetário aceita até duas casas decimais");
        if (amount.compareTo(MAX) > 0)
            throw invalid("Valor monetário acima do limite suportado");
        amount = amount.setScale(2);
    }

    /**
     * Cria o valor a partir de texto decimal com ponto, como {@code "10.50"},
     * com as regras do construtor. Lança {@link NumberFormatException} se
     * {@code amount} não for numérico.
     */
    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    /** Código ISO 4217 válido, como {@code BRL}. */
    public static String requireCurrency(String currency) {
        if (currency == null || !CURRENCY.matcher(currency).matches())
            throw invalid("Moeda deve seguir a ISO 4217, por exemplo BRL");
        return currency;
    }

    /**
     * Compara apenas os valores, sem converter moedas. Recusa com
     * {@code invalid-value} se as moedas forem diferentes.
     */
    public int compareAmount(Money other) {
        if (!currency.equals(other.currency))
            throw invalid("Valores em moedas diferentes não são comparáveis");
        return amount.compareTo(other.amount);
    }
}
