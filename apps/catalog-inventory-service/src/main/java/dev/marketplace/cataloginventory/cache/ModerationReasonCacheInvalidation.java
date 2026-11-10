package dev.marketplace.cataloginventory.cache;

import dev.marketplace.cataloginventory.application.ModerationReasonEntry;
import dev.marketplace.cataloginventory.application.ModerationReasonRepository;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Motivo criado, revisado, ativado ou desativado invalida o catálogo de motivos do tenant antes de devolver o
 * resultado, também na falha (ADR 024), como {@link CategoryCacheInvalidation} faz com a taxonomia.
 */
@Decorator
@Priority(CacheInvalidation.PRIORITY)
public class ModerationReasonCacheInvalidation implements ModerationReasonRepository {
  @Inject
  @Delegate
  ModerationReasonRepository delegate;
  @Inject
  CatalogCache cache;

  @Override
  public Uni<Void> insert(ModerationReasonEntry reason) {
    return CacheInvalidation.after(cache, delegate.insert(reason),
        () -> cache.moderationReasonsChanged(reason.tenantId()));
  }

  @Override
  public Uni<Void> update(ModerationReasonEntry reason, long expectedVersion) {
    return CacheInvalidation.after(cache, delegate.update(reason, expectedVersion),
        () -> cache.moderationReasonsChanged(reason.tenantId()));
  }

  @Override
  public Uni<List<ModerationReasonEntry>> catalog(UUID tenantId) {
    return delegate.catalog(tenantId);
  }

  @Override
  public Uni<Optional<ModerationReasonEntry>> findByCode(UUID tenantId, String code) {
    return delegate.findByCode(tenantId, code);
  }
}
