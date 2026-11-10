package dev.marketplace.cataloginventory.domain;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import static dev.marketplace.cataloginventory.domain.DomainRuleViolation.invalid;

/**
 * Cópia imutável e ordenada de valores de atributos, rejeitando chaves ou
 * valores nulos.
 */
public final class Attributes {
  private Attributes() {
  }

  /**
   * Devolve cópia imutável ordenada por código; {@code null} equivale a mapa
   * vazio. Recusa com {@code invalid-value} chave ou valor nulo.
   */
  public static Map<String, String> copy(Map<String, String> values) {
    if (values == null)
      return Map.of();
    TreeMap<String, String> sorted = new TreeMap<>();
    values.forEach((code, value) -> {
      if (code == null || value == null)
        throw invalid("Atributos não aceitam código ou valor nulo");
      sorted.put(code, value);
    });
    return Collections.unmodifiableMap(sorted);
  }
}
