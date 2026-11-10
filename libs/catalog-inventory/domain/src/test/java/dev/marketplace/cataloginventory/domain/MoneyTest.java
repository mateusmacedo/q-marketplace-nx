package dev.marketplace.cataloginventory.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {
  @Test
  void normalizesToCents() {
    assertEquals(new BigDecimal("12.30"), Money.of("12.3", "BRL").amount());
    assertEquals(Money.of("12.30", "BRL"), Money.of("12.3", "BRL"));
    assertEquals(new BigDecimal("100.00"), Money.of("1E+2", "BRL").amount());
  }

  @ParameterizedTest
  @ValueSource(strings = {"-0.01", "1.001", "100000000000000000.00"})
  void rejectsInvalidAmounts(String amount) {
    assertThrows(DomainRuleViolation.class, () -> Money.of(amount, "BRL"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"brl", "BR", "REAL", ""})
  void rejectsNonIsoCurrency(String currency) {
    assertThrows(DomainRuleViolation.class, () -> Money.of("1", currency));
  }

  @Test
  void comparesOnlySameCurrency() {
    assertTrue(Money.of("2", "BRL").compareAmount(Money.of("1", "BRL")) > 0);
    assertThrows(DomainRuleViolation.class, () -> Money.of("2", "BRL").compareAmount(Money.of("1", "USD")));
  }
}
