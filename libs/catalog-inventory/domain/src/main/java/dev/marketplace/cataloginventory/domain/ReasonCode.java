package dev.marketplace.cataloginventory.domain;

import java.util.Locale;

/**
 * Código estável de recusa. Cada camada declara os seus num enum que serve de
 * registro ({@link DomainReasons} e os da aplicação e da importação), para que
 * todo código emitido tenha texto no catálogo de mensagens do adaptador HTTP.
 */
public interface ReasonCode {
  /** Nome da constante; o enum que implementa a interface já o fornece. */
  String name();

  /** O código em kebab-case: {@code MISSING_OFFER} vira {@code missing-offer}. */
  default String code() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
