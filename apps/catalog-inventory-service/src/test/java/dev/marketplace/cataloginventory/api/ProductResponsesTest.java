package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.api.BatchModels.BatchOutcome;
import dev.marketplace.cataloginventory.api.ProductModels.ProductBatchResult;
import dev.marketplace.cataloginventory.application.Batch;
import dev.marketplace.cataloginventory.application.InventoryRepository;
import dev.marketplace.cataloginventory.application.InventoryService;
import dev.marketplace.cataloginventory.application.NotFoundException;
import dev.marketplace.cataloginventory.application.ProductRepository;
import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import dev.marketplace.cataloginventory.domain.product.Sku;
import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Resposta do lote de produtos quando a leitura do saldo, feita depois das gravações, falha ou funciona. */
class ProductResponsesTest {
    static final UUID TENANT = UUID.randomUUID();
    static final UUID STORE = UUID.randomUUID();

    /** Repositório cujos métodos devolvem todos o mesmo {@code Uni}. */
    @SuppressWarnings("unchecked")
    static <T> T answering(Class<T> type, Uni<?> answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                (proxy, method, args) -> answer);
    }

    static ProductResponses responses(Uni<?> levels) {
        InventoryService inventory = new InventoryService(answering(InventoryRepository.class, levels),
                answering(ProductRepository.class, Uni.createFrom().nothing()), Clock.systemUTC());
        return new ProductResponses(inventory, null, null, null, Clock.systemUTC());
    }

    static Product product(ProductStatus status, String sellerSku) {
        Instant now = Instant.parse("2026-11-08T12:00:00Z");
        return Product.restore(UUID.randomUUID(), TENANT, STORE, UUID.randomUUID(), "BRL", "Camiseta", "Algodão",
                Map.of(), List.of(Sku.create(UUID.randomUUID(), sellerSku, Map.of(), false, null)), status, 3, now,
                now, null);
    }

    static List<ProductBatchResult> batch(ProductResponses responses, Product active, UUID missing, Product paused) {
        List<Batch.Outcome<Product>> outcomes = List.of(
                new Batch.Outcome<>(active.id(), Optional.of(active), Optional.empty()),
                new Batch.Outcome<>(missing, Optional.empty(), Optional.of(new NotFoundException("Produto"))),
                new Batch.Outcome<>(paused.id(), Optional.of(paused), Optional.empty()));
        return responses.batch(outcomes, "/products/batch").await().indefinitely().results();
    }

    @Test
    void stockReadFailureAfterTheWritesKeepsEveryOutcomeWithTheRecordedStatus() {
        Product active = product(ProductStatus.ACTIVE, "CAM-P");
        Product paused = product(ProductStatus.PAUSED_BY_SELLER, "CAM-M");
        UUID missing = UUID.randomUUID();
        List<ProductBatchResult> results = batch(responses(Uni.createFrom().failure(new TimeoutException())), active,
                missing, paused);

        assertEquals(List.of(active.id(), missing, paused.id()), results.stream().map(ProductBatchResult::id).toList());
        assertEquals(BatchOutcome.SUCCEEDED, results.get(0).outcome());
        assertEquals(ProductStatus.ACTIVE, results.get(0).status());
        assertEquals(3L, results.get(0).version());
        assertEquals(BatchOutcome.FAILED, results.get(1).outcome());
        assertEquals(404, results.get(1).problem().status());
        assertNull(results.get(1).status());
        assertEquals(ProductStatus.PAUSED_BY_SELLER, results.get(2).status());
    }

    @Test
    void stockReadKeepsTheEffectiveStatus() {
        Product active = product(ProductStatus.ACTIVE, "CAM-P");
        Product paused = product(ProductStatus.PAUSED_BY_SELLER, "CAM-M");
        List<ProductBatchResult> results = batch(responses(Uni.createFrom().item(Map.of())), active,
                UUID.randomUUID(), paused);

        assertEquals(ProductStatus.OUT_OF_STOCK, results.get(0).status());
        assertEquals(ProductStatus.PAUSED_BY_SELLER, results.get(2).status());
    }
}
