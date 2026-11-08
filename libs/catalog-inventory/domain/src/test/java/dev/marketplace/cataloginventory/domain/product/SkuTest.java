package dev.marketplace.cataloginventory.domain.product;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SkuTest {
    static final Dimensions BOX = new Dimensions(new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30.5"),
            500);

    @Test
    void physicalSkuRequiresDimensionsAndDigitalSkuHasNone() {
        var error = assertThrows(DomainRuleViolation.class,
                () -> Sku.create(UUID.randomUUID(), "A", Map.of(), true, null));
        assertEquals("missing-dimensions", error.code());
        assertDoesNotThrow(() -> Sku.create(UUID.randomUUID(), "E-BOOK", Map.of(), false, null));
        assertThrows(DomainRuleViolation.class, () -> Sku.create(UUID.randomUUID(), "E-BOOK", Map.of(), false, BOX));
    }

    @Test
    void rejectsInvalidSellerSku() {
        assertThrows(DomainRuleViolation.class, () -> Sku.create(UUID.randomUUID(), "A B", Map.of(), false, null));
    }

    @Test
    void rejectsNullAttributeValue() {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("cor", null);
        assertThrows(DomainRuleViolation.class, () -> Sku.create(UUID.randomUUID(), "A", attrs, false, null));
    }
}
