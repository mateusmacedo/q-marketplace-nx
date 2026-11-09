package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.product.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static dev.marketplace.cataloginventory.application.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductBatchTest {
    final Catalog catalog = new Catalog();
    final ProductService products = catalog.products;
    final Actor seller = catalog.seller;

    static Batch.Item entry(Product product) {
        return new Batch.Item(product.id(), Optional.of(product.version()));
    }

    @Test
    void eachProductRunsAloneWithTheRulesOfTheSingleEndpoint() {
        Product active = catalog.active(sku("ATV-P", "P"));
        Product draft = catalog.draft(sku("RAS-P", "P"));
        Product otherStore = item(products.create(seller(UUID.randomUUID()), catalog.category, "BRL", "Alheio", "",
                Map.of("marca", "Acme"), List.of(sku("OUT-P", "P"))));
        Product stale = catalog.active(sku("VEL-P", "P"));
        List<Batch.Outcome<Product>> outcomes = item(products.batch(seller, ProductBatchAction.PAUSE,
                List.of(entry(active), entry(draft), entry(otherStore),
                        new Batch.Item(stale.id(), Optional.of(stale.version() - 1)))));
        assertEquals(ProductStatus.PAUSED_BY_SELLER, outcomes.get(0).value().orElseThrow().status());
        assertEquals(active.version() + 1, outcomes.get(0).value().orElseThrow().version());
        assertEquals("invalid-transition", ((DomainRuleViolation) outcomes.get(1).failure().orElseThrow()).code());
        assertInstanceOf(NotFoundException.class, outcomes.get(2).failure().orElseThrow(), "outra loja é 404");
        assertInstanceOf(VersionMismatchException.class, outcomes.get(3).failure().orElseThrow());
        assertEquals(ProductStatus.ACTIVE, item(products.get(seller, stale.id())).status(),
                "a falha de um item não grava nada nele");
        assertEquals(List.of(ProductTransition.CREATE, ProductTransition.SUBMIT, ProductTransition.APPROVE,
                ProductTransition.PAUSE), item(products.history(seller, active.id())).stream()
                .map(StatusChange::transition).toList(), "o histórico registra a pausa como no endpoint de um item");
    }

    @Test
    void submitArchiveAndResumeUseTheirOwnChecks() {
        Product incomplete = catalog.draft(sku("INC-P", "P"));
        Product complete = catalog.offered(catalog.draft(sku("COM-P", "P")));
        List<Batch.Outcome<Product>> submitted = item(products.batch(seller, ProductBatchAction.SUBMIT,
                List.of(entry(incomplete), entry(complete))));
        assertEquals(List.of("missing-offer:INC-P"),
                ((DomainRuleViolation) submitted.get(0).failure().orElseThrow()).reasons());
        assertEquals(ProductStatus.PENDING_MODERATION, submitted.get(1).value().orElseThrow().status());
        assertTrue(submitted.get(1).value().orElseThrow().moderationQueuedAt().isPresent());

        List<Batch.Outcome<Product>> archived = item(products.batch(seller, ProductBatchAction.ARCHIVE,
                List.of(new Batch.Item(incomplete.id(), Optional.empty()))));
        assertEquals(ProductStatus.ARCHIVED, archived.get(0).value().orElseThrow().status());

        Product paused = catalog.active(sku("PAU-P", "P"));
        item(products.pause(seller, paused.id(), Optional.empty()));
        catalog.store.sellers.clear();
        assertEquals("seller-not-eligible", ((DomainRuleViolation) item(products.batch(seller,
                ProductBatchAction.RESUME, List.of(new Batch.Item(paused.id(), Optional.empty())))).get(0)
                .failure().orElseThrow()).code());
    }

    @Test
    void requestIsRefusedWhenItemsAreEmptyTooManyRepeatedOrTheCallerIsNotASeller() {
        Product draft = catalog.draft(sku("REP-P", "P"));
        failure(products.batch(seller, ProductBatchAction.ARCHIVE, List.of()), InvalidInputException.class);
        List<Batch.Item> tooMany = IntStream.rangeClosed(0, Batch.MAX_ITEMS)
                .mapToObj(i -> new Batch.Item(UUID.randomUUID(), Optional.empty())).toList();
        failure(products.batch(seller, ProductBatchAction.ARCHIVE, tooMany), InvalidInputException.class);
        var repeated = failure(products.batch(seller, ProductBatchAction.ARCHIVE,
                List.of(entry(draft), new Batch.Item(draft.id(), Optional.empty()))), InvalidInputException.class);
        assertTrue(repeated.getMessage().contains(draft.id().toString()));
        failure(products.batch(moderator(), ProductBatchAction.ARCHIVE, List.of(entry(draft))),
                AccessDeniedException.class);
        assertEquals(ProductStatus.DRAFT, item(products.get(seller, draft.id())).status());
    }
}
