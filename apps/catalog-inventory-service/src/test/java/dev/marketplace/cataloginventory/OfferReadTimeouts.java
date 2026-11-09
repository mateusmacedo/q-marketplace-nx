package dev.marketplace.cataloginventory;

import dev.marketplace.cataloginventory.application.OfferRepository;
import dev.marketplace.cataloginventory.domain.offer.Offer;
import io.smallrye.mutiny.TimeoutException;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leitura das ofertas de vários SKUs, a que monta a resposta de um produto, estourando o prazo nos tenants marcados,
 * com a mesma falha do prazo do {@code PgSupport}; os demais tenants e as outras operações seguem para o repositório.
 */
@Decorator
@Priority(1)
public class OfferReadTimeouts implements OfferRepository {
    private static final Set<UUID> FAILING = ConcurrentHashMap.newKeySet();

    @Inject
    @Delegate
    OfferRepository delegate;

    static void failFor(UUID tenant) {
        FAILING.add(tenant);
    }

    static void restore(UUID tenant) {
        FAILING.remove(tenant);
    }

    @Override
    public Uni<Map<UUID, Offer>> ofSkus(UUID tenantId, Collection<UUID> skuIds) {
        return FAILING.contains(tenantId) ? Uni.createFrom().failure(new TimeoutException())
                : delegate.ofSkus(tenantId, skuIds);
    }

    @Override
    public Uni<Optional<Offer>> ofSku(UUID tenantId, UUID skuId) {
        return delegate.ofSku(tenantId, skuId);
    }

    @Override
    public Uni<List<Offer>> ofProduct(UUID tenantId, UUID productId) {
        return delegate.ofProduct(tenantId, productId);
    }

    @Override
    public Uni<Void> insert(Offer offer) {
        return delegate.insert(offer);
    }

    @Override
    public Uni<Void> update(Offer offer, long expectedVersion) {
        return delegate.update(offer, expectedVersion);
    }
}
