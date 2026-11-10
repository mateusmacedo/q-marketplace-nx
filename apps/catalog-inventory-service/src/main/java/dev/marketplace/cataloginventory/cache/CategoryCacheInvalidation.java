package dev.marketplace.cataloginventory.cache;

import dev.marketplace.cataloginventory.application.CategoryNode;
import dev.marketplace.cataloginventory.application.CategoryRepository;
import dev.marketplace.cataloginventory.domain.category.Category;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Categoria criada, revisada (inclusive ativada ou desativada), movida ou excluída invalida a taxonomia do tenant antes
 * de devolver o resultado, também na falha (ADR 024). As leituras em cache ficam em {@link TaxonomyCache}, fora do span
 * do adaptador: aqui o decorator roda dentro dele, e um acerto pareceria uma consulta ao banco.
 */
@Decorator
@Priority(CacheInvalidation.PRIORITY)
public class CategoryCacheInvalidation implements CategoryRepository {
  @Inject
  @Delegate
  CategoryRepository delegate;
  @Inject
  CatalogCache cache;

  @Override
  public Uni<Void> insert(Category category) {
    return CacheInvalidation.after(cache, delegate.insert(category),
        () -> cache.taxonomyChanged(category.tenantId()));
  }

  @Override
  public Uni<Void> update(Category category, long expectedVersion) {
    return CacheInvalidation.after(cache, delegate.update(category, expectedVersion),
        () -> cache.taxonomyChanged(category.tenantId()));
  }

  @Override
  public Uni<Void> move(Category category, List<UUID> previousAncestry, long expectedVersion) {
    return CacheInvalidation.after(cache, delegate.move(category, previousAncestry, expectedVersion),
        () -> cache.taxonomyChanged(category.tenantId()));
  }

  @Override
  public Uni<Void> delete(Category category, long expectedVersion) {
    return CacheInvalidation.after(cache, delegate.delete(category, expectedVersion),
        () -> cache.taxonomyChanged(category.tenantId()));
  }

  @Override
  public Uni<List<Category>> chain(UUID tenantId, UUID categoryId) {
    return delegate.chain(tenantId, categoryId);
  }

  @Override
  public Uni<Optional<UUID>> idByCode(UUID tenantId, String code) {
    return delegate.idByCode(tenantId, code);
  }

  @Override
  public Uni<Set<String>> descendantAttributeCodes(UUID tenantId, UUID categoryId) {
    return delegate.descendantAttributeCodes(tenantId, categoryId);
  }

  @Override
  public Uni<Integer> subtreeHeight(UUID tenantId, UUID categoryId) {
    return delegate.subtreeHeight(tenantId, categoryId);
  }

  @Override
  public Uni<List<Category>> children(UUID tenantId, Optional<UUID> parentId, Optional<String> afterCode, int limit) {
    return delegate.children(tenantId, parentId, afterCode, limit);
  }

  @Override
  public Uni<Map<UUID, Integer>> childCounts(UUID tenantId, Collection<UUID> categoryIds) {
    return delegate.childCounts(tenantId, categoryIds);
  }

  @Override
  public Uni<Map<UUID, Integer>> depths(UUID tenantId, Collection<UUID> categoryIds) {
    return delegate.depths(tenantId, categoryIds);
  }

  @Override
  public Uni<List<CategoryNode>> tree(UUID tenantId, Optional<String> afterPath, boolean usableOnly, int limit) {
    return delegate.tree(tenantId, afterPath, usableOnly, limit);
  }

  @Override
  public Uni<List<CategoryNode>> byIds(UUID tenantId, Collection<UUID> categoryIds) {
    return delegate.byIds(tenantId, categoryIds);
  }
}
