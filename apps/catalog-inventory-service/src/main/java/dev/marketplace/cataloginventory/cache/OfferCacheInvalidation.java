package dev.marketplace.cataloginventory.cache;

import dev.marketplace.cataloginventory.application.OfferRepository;
import dev.marketplace.cataloginventory.domain.offer.Offer;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Toda gravação de oferta (preço, promoção agendada ou cancelada, importação) invalida a vitrine do produto dela antes
 * de devolver o resultado, também na falha (ADR 024).
 */
@Decorator
@Priority(CacheInvalidation.PRIORITY)
public class OfferCacheInvalidation implements OfferRepository {
    @Inject
    @Delegate
    OfferRepository delegate;
    @Inject
    CatalogCache cache;

    @Override
    public Uni<Void> insert(Offer offer) {
        return CacheInvalidation.after(cache, delegate.insert(offer),
                () -> cache.productChanged(offer.tenantId(), offer.productId(), offer.storeId()));
    }

    @Override
    public Uni<Void> update(Offer offer, long expectedVersion) {
        return CacheInvalidation.after(cache, delegate.update(offer, expectedVersion),
                () -> cache.productChanged(offer.tenantId(), offer.productId(), offer.storeId()));
    }

    @Override
    public Uni<Optional<Offer>> ofSku(UUID tenantId, UUID skuId) {
        return delegate.ofSku(tenantId, skuId);
    }

    @Override
    public Uni<List<Offer>> ofProduct(UUID tenantId, UUID productId) {
        return delegate.ofProduct(tenantId, productId);
    }
}
