package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.application.InvalidInputException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
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

  /** Converte cada item da lista; item em branco ou malformado responde 400. */
  static List<UUID> uuids(List<String> values) {
    return values.stream().map(value -> uuid(value)
        .orElseThrow(() -> new InvalidInputException("Identificador inválido: " + value))).toList();
  }

  /** Converte a data opcional (ISO 8601, como 2026-11-08); ausente ou em branco resulta em vazio. */
  static Optional<LocalDate> date(String value) {
    if (value == null || value.isBlank())
      return Optional.empty();
    try {
      return Optional.of(LocalDate.parse(value));
    } catch (DateTimeParseException e) {
      throw new InvalidInputException("Data inválida: " + value);
    }
  }
}
