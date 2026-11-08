package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.application.InvalidInputException;
import java.util.Optional;
import java.util.UUID;

/**
 * Conversão de parâmetros opcionais de query; valor malformado responde 400.
 */
final class QueryParams {
    private QueryParams() {
    }

    /**
     * Converte o parâmetro opcional; ausente ou em branco resulta em vazio e valor
     * malformado
     * responde 400.
     */
    static Optional<UUID> uuid(String value) {
        if (value == null || value.isBlank())
            return Optional.empty();
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("Identificador inválido: " + value);
        }
    }
}
