package dev.marketplace.cataloginventory.cache;

import dev.marketplace.cataloginventory.application.ProductQuery;
import dev.marketplace.cataloginventory.application.ProductRepository;
import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.StatusChange;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Toda gravação de produto (criação, edição, SKU, transição, moderação, arquivamento, importação) invalida a vitrine
 * dele antes de devolver o resultado: o bloqueio sai do ar antes da resposta ao moderador (ADR 024). Invalida também
 * na falha, porque um prazo estourado não prova que o commit não aconteceu.
 */
@Decorator
@Priority(CacheInvalidation.PRIORITY)
public class ProductCacheInvalidation implements ProductRepository {
  @Inject
  @Delegate
  ProductRepository delegate;
  @Inject
  CatalogCache cache;

  @Override
  public Uni<Void> insert(Product product) {
    return CacheInvalidation.after(cache, delegate.insert(product),
        () -> cache.productChanged(product.tenantId(), product.id(), product.storeId()));
  }

  @Override
  public Uni<Void> update(Product product, long expectedVersion) {
    return CacheInvalidation.after(cache, delegate.update(product, expectedVersion),
        () -> cache.productChanged(product.tenantId(), product.id(), product.storeId()));
  }

  @Override
  public Uni<Optional<Product>> findById(UUID tenantId, UUID productId) {
    return delegate.findById(tenantId, productId);
  }

  @Override
  public Uni<Optional<Product>> findBySku(UUID tenantId, UUID skuId) {
    return delegate.findBySku(tenantId, skuId);
  }

  @Override
  public Uni<Map<String, UUID>> skuIdsBySellerSku(UUID tenantId, UUID storeId, Collection<String> sellerSkus) {
    return delegate.skuIdsBySellerSku(tenantId, storeId, sellerSkus);
  }

  @Override
  public Uni<List<Product>> moderationQueue(UUID tenantId, Optional<QueuePosition> after, int limit) {
    return delegate.moderationQueue(tenantId, after, limit);
  }

  @Override
  public Uni<List<Product>> search(UUID tenantId, ProductQuery query, int limit) {
    return delegate.search(tenantId, query, limit);
  }

  @Override
  public Uni<Map<UUID, StatusChange>> lastChanges(UUID tenantId, Collection<UUID> productIds) {
    return delegate.lastChanges(tenantId, productIds);
  }

  @Override
  public Uni<List<StatusChange>> history(UUID tenantId, UUID productId) {
    return delegate.history(tenantId, productId);
  }
}
