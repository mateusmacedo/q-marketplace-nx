package dev.marketplace.cataloginventory.domain.category;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AttributeDefinitionTest {
    static AttributeDefinition of(AttributeType type, String... options) {
        return new AttributeDefinition("attr", "Atributo", type, false, false, List.of(options));
    }

    @ParameterizedTest
    @CsvSource({ "TEXT,qualquer,true", "INTEGER,42,true", "INTEGER,4.2,false", "DECIMAL,4.2,true", "DECIMAL,abc,false",
            "BOOLEAN,true,true", "BOOLEAN,sim,false" })
    void validatesValuesByType(AttributeType type, String value, boolean accepted) {
        assertEquals(accepted, of(type).accepts(value));
    }

    @Test
    void optionAcceptsOnlyDeclaredOptions() {
        AttributeDefinition cor = of(AttributeType.OPTION, "preto", "azul");
        assertTrue(cor.accepts("azul"));
        assertFalse(cor.accepts("verde"));
        assertFalse(cor.accepts(" "));
    }

    @Test
    void rejectsInconsistentOptions() {
        assertThrows(DomainRuleViolation.class, () -> of(AttributeType.OPTION));
        assertThrows(DomainRuleViolation.class, () -> of(AttributeType.OPTION, "a", "a"));
        assertThrows(DomainRuleViolation.class, () -> of(AttributeType.TEXT, "a"));
    }

    @Test
    void rejectsInvalidCode() {
        assertThrows(DomainRuleViolation.class,
                () -> new AttributeDefinition("Cor", "Cor", AttributeType.TEXT, false, false, List.of()));
    }

    @Test
    void textHasLengthLimit() {
        assertFalse(of(AttributeType.TEXT).accepts("x".repeat(501)));
    }
}
