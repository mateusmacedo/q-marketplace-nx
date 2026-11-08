package dev.marketplace.cataloginventory.domain.category;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Duration;
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

    @ParameterizedTest
    @CsvSource({ "INTEGER,-123456789012345678,true", "INTEGER,1234567890123456789,false", "INTEGER,+5,false",
            "DECIMAL,-123456789012345678.123456,true", "DECIMAL,1234567890123456789,false", "DECIMAL,1.1234567,false",
            "DECIMAL,1E3,false", "DECIMAL,.5,false", "DECIMAL,5.,false" })
    void numbersAreBoundedAndInPlainNotation(AttributeType type, String value, boolean accepted) {
        assertEquals(accepted, of(type).accepts(value));
    }

    @Test
    void rejectsHugeNumbersWithoutParsingThem() {
        String huge = "9".repeat(1_000_000);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            assertFalse(of(AttributeType.INTEGER).accepts(huge));
            assertFalse(of(AttributeType.DECIMAL).accepts(huge));
            assertFalse(of(AttributeType.DECIMAL).accepts(huge + ".5"));
        });
    }
}
