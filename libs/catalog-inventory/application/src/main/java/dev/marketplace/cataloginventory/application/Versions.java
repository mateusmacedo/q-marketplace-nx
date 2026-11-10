package dev.marketplace.cataloginventory.application;

import java.util.Optional;

final class Versions {
  private Versions() {
  }

  /**
   * Confere a versão esperada pelo cliente (If-Match) contra a atual; ausente
   * dispensa a checagem. Versão diferente lança
   * {@link VersionMismatchException} com a versão atual.
   */
  static void requireMatch(Optional<Long> expected, long current) {
    if (expected.isPresent() && expected.get() != current)
      throw new VersionMismatchException(current);
  }
}
