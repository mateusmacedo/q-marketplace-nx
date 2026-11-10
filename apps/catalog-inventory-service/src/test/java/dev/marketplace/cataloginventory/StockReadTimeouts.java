package dev.marketplace.cataloginventory;

import dev.marketplace.cataloginventory.application.InventoryRepository;
import dev.marketplace.cataloginventory.domain.inventory.SkuCandidate;
import dev.marketplace.cataloginventory.domain.inventory.StockLevel;
import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leitura de saldo que estoura o prazo nos tenants marcados, com a mesma falha
 * do prazo do {@code PgSupport}; os
 * demais tenants, o ajuste de estoque e a leitura da reserva seguem para o
 * repositório.
 */
@Decorator
@Priority(1)
public class StockReadTimeouts implements InventoryRepository {
    private static final Set<UUID> FAILING = ConcurrentHashMap.newKeySet();

    @Inject
    @Delegate
    InventoryRepository delegate;

    static void failFor(UUID tenant) {
        FAILING.add(tenant);
    }

    static void restore(UUID tenant) {
        FAILING.remove(tenant);
    }

    @Override
    public Uni<Map<UUID, StockLevel>> levels(UUID tenantId, Collection<UUID> skuIds) {
        return FAILING.contains(tenantId) ? Uni.createFrom().failure(new TimeoutException())
                : delegate.levels(tenantId, skuIds);
    }

    @Override
    public Uni<StockLevel> adjust(UUID tenantId, UUID storeId, UUID productId, UUID skuId, int onHand,
            Optional<Long> expectedVersion, String actor, Instant at) {
        return delegate.adjust(tenantId, storeId, productId, skuId, onHand, expectedVersion, actor, at);
    }

    @Override
    public Uni<Map<UUID, SkuCandidate>> candidates(UUID tenantId, Collection<UUID> skuIds) {
        return delegate.candidates(tenantId, skuIds);
    }
}
