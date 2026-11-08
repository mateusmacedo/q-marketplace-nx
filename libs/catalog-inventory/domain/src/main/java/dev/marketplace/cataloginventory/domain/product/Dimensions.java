package dev.marketplace.cataloginventory.domain.product;

import java.math.BigDecimal;
import static dev.marketplace.cataloginventory.domain.DomainRuleViolation.invalid;

/**
 * Dimensões (cm) e peso (g) da embalagem, exigidos para cálculo de frete de
 * SKUs físicos.
 */
public record Dimensions(BigDecimal heightCm, BigDecimal widthCm, BigDecimal lengthCm, int weightGrams) {
    public static final BigDecimal MAX_CM = new BigDecimal("1000");
    public static final int MAX_WEIGHT_GRAMS = 1_000_000;

    /**
     * Normaliza as medidas para duas casas. Recusa com {@code invalid-value}
     * medida nula, não positiva, acima de {@link #MAX_CM} ou com mais de duas
     * casas, e peso fora de 1 a {@link #MAX_WEIGHT_GRAMS}.
     */
    public Dimensions {
        heightCm = requireMeasure("altura", heightCm);
        widthCm = requireMeasure("largura", widthCm);
        lengthCm = requireMeasure("comprimento", lengthCm);
        if (weightGrams <= 0 || weightGrams > MAX_WEIGHT_GRAMS)
            throw invalid("Peso deve estar entre 1 e " + MAX_WEIGHT_GRAMS + " gramas");
    }

    private static BigDecimal requireMeasure(String name, BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.compareTo(MAX_CM) > 0
                || value.stripTrailingZeros().scale() > 2)
            throw invalid(
                    "Medida de " + name + " deve ser positiva, até " + MAX_CM + " cm e com até duas casas decimais");
        return value.setScale(2);
    }
}
