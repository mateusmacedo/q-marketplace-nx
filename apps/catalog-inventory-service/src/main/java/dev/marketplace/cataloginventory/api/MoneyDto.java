package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.domain.Money;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

/** Valor monetário exato, com até duas casas, na moeda ISO 4217 informada. */
public record MoneyDto(@NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = Money.CURRENCY_PATTERN) String currency) {
    Money toDomain() {
        return new Money(amount, currency);
    }

    static MoneyDto of(Money m) {
        return m == null ? null : new MoneyDto(m.amount(), m.currency());
    }

    /** Converte o valor recebido; nulo (campo opcional ausente) continua nulo. */
    static Money domainOf(MoneyDto dto) {
        return dto == null ? null : dto.toDomain();
    }
}
