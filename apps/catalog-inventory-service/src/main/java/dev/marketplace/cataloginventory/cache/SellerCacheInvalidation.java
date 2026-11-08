package dev.marketplace.cataloginventory.cache;

import dev.marketplace.cataloginventory.application.SellerEligibilityRepository;
import dev.marketplace.cataloginventory.application.SellerVerdict;
import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * Veredito KYB/KYC aplicado invalida a vitrine de todos os produtos da loja antes de a mensagem ser confirmada; entrega
 * repetida ou veredito obsoleto não mudam a loja e não invalidam. Na falha invalida também (ADR 024).
 */
@Decorator
@Priority(CacheInvalidation.PRIORITY)
public class SellerCacheInvalidation implements SellerEligibilityRepository {
    @Inject
    @Delegate
    SellerEligibilityRepository delegate;
    @Inject
    CatalogCache cache;

    @Override
    public Uni<SellerVerdict.Outcome> apply(String consumer, SellerVerdict verdict) {
        return CacheInvalidation.when(cache, delegate.apply(consumer, verdict),
                (outcome, failure) -> failure != null || outcome == SellerVerdict.Outcome.APPLIED,
                () -> cache.storeChanged(verdict.tenantId(), verdict.storeId()));
    }

    @Override
    public Uni<SellerStatus> statusOf(UUID tenantId, UUID storeId) {
        return delegate.statusOf(tenantId, storeId);
    }
}
