package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.application.ApplicationReasons;
import dev.marketplace.cataloginventory.domain.DomainReasons;
import dev.marketplace.cataloginventory.domain.Reason;
import dev.marketplace.cataloginventory.domain.ReasonCode;
import dev.marketplace.cataloginventory.imports.ImportFileReasons;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasonMessagesTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");
    private static final Pattern WITH_ARITY = Pattern.compile("(.+)\\.(\\d+)");

    // WHY: os registros por camada são a fonte dos códigos que podem chegar ao cliente; registro novo entra aqui.
    static Set<String> registeredCodes() {
        return Stream.of(DomainReasons.values(), ApplicationReasons.values(), ImportFileReasons.values())
                .flatMap(Stream::of).map(ReasonCode::code).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void everyRegisteredCodeHasAMessageWithoutParameters() {
        Set<String> missing = new TreeSet<>(registeredCodes());
        missing.removeAll(ReasonMessages.keys());
        assertEquals(Set.of(), missing, "códigos sem texto em " + ReasonMessages.BUNDLE);
    }

    @Test
    void everyMessageBelongsToARegisteredCodeAndUsesOnlyItsParameters() {
        Set<String> codes = registeredCodes();
        for (String key : ReasonMessages.keys()) {
            Matcher arity = WITH_ARITY.matcher(key);
            String code = arity.matches() ? arity.group(1) : key;
            int params = arity.matches() ? Integer.parseInt(arity.group(2)) : 0;
            assertTrue(codes.contains(code), key + " não corresponde a um código registrado");
            String message = ReasonMessages.format(code, Collections.nCopies(params, "x")).orElseThrow();
            assertFalse(message.isBlank(), key);
            Matcher placeholder = PLACEHOLDER.matcher(message);
            assertFalse(placeholder.find(), key + " usa parâmetro além dos " + params + " do motivo");
        }
    }

    @Test
    void theEmitterMessageWinsThenTheCatalogWithParametersThenTheFallback() {
        assertEquals("Preencha o atributo Marca.",
                ReasonMessages.resolve(Reason.of(DomainReasons.MISSING_ATTRIBUTE, "marca")
                        .withMessage("Preencha o atributo Marca."), "detalhe"));
        assertEquals("Preencha o atributo marca.",
                ReasonMessages.resolve(Reason.of(DomainReasons.MISSING_ATTRIBUTE, "marca"), "detalhe"));
        assertEquals("A árvore de categorias aceita até 6 níveis e passaria disso nesse lugar.",
                ReasonMessages.resolve(Reason.of(DomainReasons.CATEGORY_TOO_DEEP, 6), "detalhe"));
        assertEquals("Defina o preço de todos os SKUs.",
                ReasonMessages.resolve(Reason.of(DomainReasons.MISSING_OFFER, "A", "B"), "detalhe"));
        assertEquals("detalhe", ReasonMessages.resolve(new Reason("not-registered", List.of(), null), "detalhe"));
    }
}
