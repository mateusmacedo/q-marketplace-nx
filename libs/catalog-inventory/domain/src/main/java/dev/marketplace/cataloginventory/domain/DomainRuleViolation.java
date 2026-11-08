package dev.marketplace.cataloginventory.domain;

import java.util.List;

/**
 * Recusa de negócio tipada. {@code code} é estável para clientes e backoffice;
 * {@code reasons} lista motivos
 * identificáveis (ex.: {@code missing-attribute:cor}). Não é falha técnica e
 * nunca deve disparar retentativa.
 */
public class DomainRuleViolation extends RuntimeException {
    private final String code;
    private final List<String> reasons;

    public DomainRuleViolation(String code, String message) {
        this(code, message, List.of());
    }

    /**
     * Cria a recusa guardando {@code reasons} em cópia imutável; a lista não
     * pode ser nula.
     */
    public DomainRuleViolation(String code, String message, List<String> reasons) {
        super(message);
        this.code = code;
        this.reasons = List.copyOf(reasons);
    }

    public String code() {
        return code;
    }

    public List<String> reasons() {
        return reasons;
    }

    /**
     * Cria a recusa genérica de valor inválido, com código
     * {@code invalid-value} e sem motivos detalhados.
     */
    public static DomainRuleViolation invalid(String message) {
        return new DomainRuleViolation("invalid-value", message);
    }

    /**
     * Devolve {@code value} se tiver conteúdo e até {@code max} caracteres; nulo,
     * em branco ou longo demais recusa com {@code invalid-value} e
     * {@code message}.
     */
    public static String requireText(String value, int max, String message) {
        if (value == null || value.isBlank() || value.length() > max)
            throw invalid(message);
        return value;
    }
}
