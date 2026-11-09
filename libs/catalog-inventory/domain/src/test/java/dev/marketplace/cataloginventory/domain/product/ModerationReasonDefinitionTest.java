package dev.marketplace.cataloginventory.domain.product;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import org.junit.jupiter.api.Test;
import java.util.EnumSet;
import java.util.Set;
import static dev.marketplace.cataloginventory.domain.product.ProductTransition.APPROVE;
import static dev.marketplace.cataloginventory.domain.product.ProductTransition.BLOCK;
import static dev.marketplace.cataloginventory.domain.product.ProductTransition.REJECT;
import static org.junit.jupiter.api.Assertions.*;

class ModerationReasonDefinitionTest {
    @Test
    void codeIsKebabCaseWithLettersOnly() {
        assertEquals("wrong-category", define("wrong-category").code());
        for (String code : new String[] { "Wrong", "wrong--category", "-wrong", "wrong1", "wrong_category", "",
                "a".repeat(ModerationReasonDefinition.CODE_MAX_LENGTH + 1) })
            assertEquals("invalid-value", assertThrows(DomainRuleViolation.class, () -> define(code)).code(), code);
        assertEquals("invalid-value", assertThrows(DomainRuleViolation.class, () -> define(null)).code());
    }

    @Test
    void labelIsTrimmedAndBounded() {
        assertEquals("Categoria incorreta",
                new ModerationReasonDefinition("wrong-category", "  Categoria incorreta ", Set.of(REJECT), true)
                        .label());
        assertThrows(DomainRuleViolation.class,
                () -> new ModerationReasonDefinition("wrong-category", "  ", Set.of(REJECT), true));
        assertThrows(DomainRuleViolation.class, () -> new ModerationReasonDefinition("wrong-category",
                "x".repeat(ModerationReasonDefinition.LABEL_MAX_LENGTH + 1), Set.of(REJECT), true));
    }

    @Test
    void onlyRejectionAndBlockTakeReasons() {
        assertThrows(DomainRuleViolation.class,
                () -> new ModerationReasonDefinition("wrong-category", "Categoria", Set.of(), true));
        assertThrows(DomainRuleViolation.class,
                () -> new ModerationReasonDefinition("wrong-category", "Categoria", Set.of(REJECT, APPROVE), true));
        ModerationReasonDefinition both = new ModerationReasonDefinition("price", "Preço",
                EnumSet.of(BLOCK, REJECT), true);
        assertTrue(both.appliesTo(REJECT));
        assertTrue(both.appliesTo(BLOCK));
        assertFalse(define("wrong-category").appliesTo(BLOCK));
    }

    @Test
    void revisionKeepsTheCodeAndTheSituation() {
        ModerationReasonDefinition inactive = define("wrong-category").withActive(false);
        ModerationReasonDefinition revised = inactive.revised("Categoria errada", Set.of(REJECT, BLOCK));
        assertEquals("wrong-category", revised.code());
        assertEquals("Categoria errada", revised.label());
        assertFalse(revised.active());
        assertTrue(revised.withActive(true).active());
    }

    static ModerationReasonDefinition define(String code) {
        return new ModerationReasonDefinition(code, "Categoria incorreta", Set.of(REJECT), true);
    }
}
