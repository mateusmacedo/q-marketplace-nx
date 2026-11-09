package dev.marketplace.cataloginventory.domain.product;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.Reason;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static dev.marketplace.cataloginventory.domain.product.ProductTransition.BLOCK;
import static dev.marketplace.cataloginventory.domain.product.ProductTransition.REJECT;
import static org.junit.jupiter.api.Assertions.*;

class DecisionReasonTest {
    static final List<ModerationReasonDefinition> CATALOG = List.of(
            reason("wrong-category", "Categoria incorreta", REJECT),
            reason("price-out-of-range", "Preço fora do padrão", REJECT, BLOCK),
            reason("prohibited-item", "Item proibido na plataforma", BLOCK),
            reason("counterfeit", "Possível falsificação", REJECT, BLOCK).withActive(false));

    static ModerationReasonDefinition reason(String code, String label, ProductTransition... decisions) {
        return new ModerationReasonDefinition(code, label, Set.of(decisions), true);
    }

    @Test
    void keepsTheCatalogOrderTheLabelsOfTheTimeAndTheNoteApart() {
        DecisionReason reason = DecisionReason.of(REJECT, CATALOG,
                List.of("price-out-of-range", "wrong-category", "wrong-category"),
                Optional.of("  A gola não bate com a foto  "));
        assertEquals(List.of("wrong-category", "price-out-of-range"), reason.codes(),
                "ordem do catálogo, sem repetição");
        assertEquals(List.of(new ReasonLabel("wrong-category", "Categoria incorreta"),
                new ReasonLabel("price-out-of-range", "Preço fora do padrão")), reason.reasons());
        assertEquals("A gola não bate com a foto", reason.note());
        assertEquals("Categoria incorreta. Preço fora do padrão. A gola não bate com a foto", reason.text());

        DecisionReason onlyReason = DecisionReason.of(BLOCK, CATALOG, List.of("prohibited-item"), Optional.empty());
        assertNull(onlyReason.note());
        assertEquals("Item proibido na plataforma.", onlyReason.text());
        DecisionReason onlyNote = DecisionReason.of(BLOCK, List.of(), List.of(), Optional.of("Só o complemento"));
        assertEquals(List.of(), onlyNote.reasons());
        assertEquals("Só o complemento", onlyNote.text(), "tenant sem catálogo decide só com o complemento");
    }

    @Test
    void refusesCodesOutsideTheCatalogDeactivatedOrOfTheOtherDecision() {
        DomainRuleViolation error = assertThrows(DomainRuleViolation.class, () -> DecisionReason.of(REJECT, CATALOG,
                List.of("prohibited-item", "spam", "counterfeit", "wrong-category"), Optional.empty()));
        assertEquals("invalid-moderation-reason", error.code());
        assertEquals(List.of("reason-not-applicable:prohibited-item", "unknown-reason:spam",
                "reason-inactive:counterfeit"), error.reasons());
        List<String> messages = error.details().stream().map(Reason::message).toList();
        assertEquals("O motivo \"Item proibido na plataforma\" não vale para a rejeição.", messages.get(0));
        assertNull(messages.get(1), "o código fora do catálogo não tem rótulo; o texto vem do catálogo de mensagens");
        assertEquals("O motivo \"Possível falsificação\" foi desativado. Recarregue a lista de motivos.",
                messages.get(2));
        assertEquals(List.of("reason-not-applicable:wrong-category"), assertThrows(DomainRuleViolation.class,
                () -> DecisionReason.of(BLOCK, CATALOG, List.of("wrong-category"), Optional.empty())).reasons());
        assertThrows(IllegalArgumentException.class, () -> DecisionReason.of(ProductTransition.APPROVE, CATALOG,
                List.of(), Optional.of("aprovar não tem motivo")));
    }

    @Test
    void theLimitBelongsToTheNoteEvenWithManyReasons() {
        String note = "x".repeat(DecisionReason.NOTE_MAX_LENGTH);
        DecisionReason reason = DecisionReason.of(REJECT, CATALOG, List.of("wrong-category", "price-out-of-range"),
                Optional.of(note));
        assertEquals(DecisionReason.NOTE_MAX_LENGTH, reason.note().length());
        assertTrue(reason.text().length() > DecisionReason.NOTE_MAX_LENGTH, "o texto derivado não tem teto próprio");
        assertEquals("invalid-value", assertThrows(DomainRuleViolation.class, () -> DecisionReason.of(REJECT, CATALOG,
                List.of("wrong-category"), Optional.of(note + "x"))).code());
        assertEquals("invalid-value", assertThrows(DomainRuleViolation.class,
                () -> DecisionReason.of(REJECT, CATALOG, List.of(), Optional.of("   "))).code());
    }

    @Test
    void theTextOfARecordedDecisionIsDerived() {
        assertNull(DecisionReason.compose(List.of(), null), "transição sem motivo");
        assertEquals("Categoria incorreta. Nota",
                DecisionReason.compose(List.of(new ReasonLabel("wrong-category", "Categoria incorreta")), "Nota"));
    }
}
