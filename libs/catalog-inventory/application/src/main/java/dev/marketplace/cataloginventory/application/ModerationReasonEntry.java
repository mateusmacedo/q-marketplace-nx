package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.product.ModerationReasonDefinition;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Motivo do catálogo de um tenant: a definição, a posição, que ordena a lista e o texto das decisões (com o código
 * como desempate), e a versão do controle otimista.
 */
public record ModerationReasonEntry(UUID tenantId, ModerationReasonDefinition definition, int position, long version,
    Instant createdAt, Instant updatedAt) {
  public static final int MAX_POSITION = 9_999;

  public ModerationReasonEntry {
    Objects.requireNonNull(tenantId, "tenant obrigatório");
    Objects.requireNonNull(definition, "definição obrigatória");
    Objects.requireNonNull(createdAt, "data de criação obrigatória");
    Objects.requireNonNull(updatedAt, "data de atualização obrigatória");
  }

  public String code() {
    return definition.code();
  }

  /** Próxima versão, com a definição e a posição pedidas. */
  ModerationReasonEntry next(ModerationReasonDefinition revised, int newPosition, Instant now) {
    return new ModerationReasonEntry(tenantId, revised, newPosition, version + 1, createdAt, now);
  }
}
