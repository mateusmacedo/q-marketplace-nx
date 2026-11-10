package dev.marketplace.cataloginventory.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DomainRuleViolationTest {
  @Test
  void reasonsKeepTheCompactFormAndDetailsCarryCodeAndParams() {
    var violation = new DomainRuleViolation(DomainReasons.PRODUCT_NOT_PUBLISHABLE, "Produto incompleto",
        List.of(Reason.of(DomainReasons.NO_SKU),
            Reason.of(DomainReasons.MISSING_VARIANT_ATTRIBUTE, "CAM-P", "cor")));
    assertEquals("product-not-publishable", violation.code());
    assertEquals(List.of("no-sku", "missing-variant-attribute:CAM-P:cor"), violation.reasons());
    assertEquals(List.of("CAM-P", "cor"), violation.details().get(1).params());
  }

  @Test
  void withoutDetailedReasonsTheViolationItselfIsTheDetail() {
    var violation = new DomainRuleViolation(Reason.of(DomainReasons.CATEGORY_TOO_DEEP, 6), "Até 6 níveis");
    assertEquals(List.of(), violation.reasons());
    assertEquals(List.of(new Reason("category-too-deep", List.of("6"), null)), violation.details());
  }

  @Test
  void invalidValueCarriesItsMessageAsTheTextForTheUser() {
    var violation = DomainRuleViolation.invalid("Título tem até 200 caracteres");
    assertEquals("invalid-value", violation.code());
    assertEquals("Título tem até 200 caracteres", violation.reason().message());
    assertNull(Reason.of(DomainReasons.NO_SKU).message());
  }
}
