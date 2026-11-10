package dev.marketplace.cataloginventory.application;

import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta do catálogo de motivos de moderação, um por tenant. Não há exclusão: o código vive no histórico. */
public interface ModerationReasonRepository {
  /** Catálogo do tenant, ativos e inativos, por posição e, no empate, por código. */
  Uni<List<ModerationReasonEntry>> catalog(UUID tenantId);

  Uni<Optional<ModerationReasonEntry>> findByCode(UUID tenantId, String code);

  /** Código já usado no tenant: {@link DuplicateException} {@code duplicate-moderation-reason-code}. */
  Uni<Void> insert(ModerationReasonEntry reason);

  /** Grava se a versão gravada ainda é {@code expectedVersion}; senão, {@link ConcurrentUpdateException}. */
  Uni<Void> update(ModerationReasonEntry reason, long expectedVersion);
}
