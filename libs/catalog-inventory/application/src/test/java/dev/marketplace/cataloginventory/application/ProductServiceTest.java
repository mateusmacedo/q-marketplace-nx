package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.InvalidTransitionException;
import dev.marketplace.cataloginventory.domain.product.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.marketplace.cataloginventory.application.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductServiceTest {
    final Catalog catalog = new Catalog();
    final InMemoryStore store = catalog.store;
    final ProductService products = catalog.products;
    final ModerationService moderation = catalog.moderation;
    final UUID storeId = catalog.storeId;
    final Actor seller = catalog.seller;
    final UUID category = catalog.category;

    Product draft() {
        return catalog.draft(sku("CAM-P", "P"), sku("CAM-M", "M"));
    }

    @Test
    void sellerCreatesDraftInOwnStore() {
        Product product = draft();
        assertEquals(storeId, product.storeId());
        assertEquals(ProductStatus.DRAFT, product.status());
        assertEquals(0, product.version());
        assertEquals(List.of(ProductTransition.CREATE),
                item(products.history(seller, product.id())).stream().map(StatusChange::transition).toList());
    }

    @Test
    void storeComesOnlyFromTheActor() {
        Actor noStore = new Actor(TENANT, Optional.empty(), "s", Set.of(Capability.MANAGE_OWN_PRODUCTS));
        failure(products.create(noStore, category, "BRL", "t", "", Map.of(), List.of()), AccessDeniedException.class);
        failure(products.create(moderator(), category, "BRL", "t", "", Map.of(), List.of()),
                AccessDeniedException.class);
    }

    @Test
    void otherStoreAndOtherTenantSeeNotFoundButModeratorSees() {
        Product product = draft();
        failure(products.get(seller(UUID.randomUUID()), product.id()), NotFoundException.class);
        failure(products.pause(seller(UUID.randomUUID()), product.id(), Optional.empty()), NotFoundException.class);
        Actor otherTenant = new Actor(UUID.randomUUID(), Optional.of(storeId), "s",
                Set.of(Capability.MANAGE_OWN_PRODUCTS));
        failure(products.get(otherTenant, product.id()), NotFoundException.class);
        assertEquals(product.id(), item(products.get(moderator(), product.id())).id());
    }

    @Test
    void sellerSkuIsUniqueAcrossTheStore() {
        draft();
        failure(products.create(seller, category, "BRL", "Outra", "", Map.of("marca", "Acme"),
                List.of(sku("CAM-P", "G"))),
                DuplicateException.class);
    }

    @Test
    void approvalRequiresEligibleSeller() {
        Product product = catalog.offered(draft());
        item(products.submit(seller, product.id(), Optional.empty()));
        var error = failure(moderation.approve(moderator(), product.id(), Optional.empty()),
                DomainRuleViolation.class);
        assertEquals("seller-not-eligible", error.code());
        catalog.approveSeller();
        assertEquals(ProductStatus.ACTIVE,
                item(moderation.approve(moderator(), product.id(), Optional.empty())).status());
    }

    @Test
    void sellerCannotModerate() {
        Product product = draft();
        failure(moderation.approve(seller, product.id(), Optional.empty()), AccessDeniedException.class);
        failure(moderation.queue(seller, Optional.empty(), 10), AccessDeniedException.class);
    }

    @Test
    void staleIfMatchIsRefusedWithCurrentVersion() {
        Product product = catalog.offered(draft());
        item(products.submit(seller, product.id(), Optional.of(0L)));
        var error = failure(products.archive(seller, product.id(), Optional.of(0L)), VersionMismatchException.class);
        assertTrue(error.getMessage().contains("1"));
    }

    @Test
    void lostUpdateIsDetectedAtWriteTime() {
        Product product = draft();
        store.beforeProductUpdate = changed -> store.products.get(changed.id()).markPersisted(changed.version() + 1);
        failure(products.pause(seller, product.id(), Optional.empty()), InvalidTransitionException.class);
        failure(products.archive(seller, product.id(), Optional.empty()), ConcurrentUpdateException.class);
    }

    @Test
    void contentEditSendsPublishedProductBackToModerationQueue() {
        Product product = catalog.active(sku("CAM-P", "P"), sku("CAM-M", "M"));
        item(products.reviseContent(seller, product.id(), Optional.empty(), category, "Camiseta premium", "Pima",
                Map.of("marca", "Acme")));
        Page<Product> queue = item(moderation.queue(moderator(), Optional.empty(), 10));
        assertEquals(List.of(product.id()), queue.items().stream().map(Product::id).toList());
    }

    @Test
    void submitReportsAllReasons() {
        Product product = item(products.create(seller, category, "BRL", "Camiseta", "", Map.of(), List.of()));
        var error = failure(products.submit(seller, product.id(), Optional.empty()), DomainRuleViolation.class);
        assertEquals(List.of("no-sku", "missing-attribute:marca"), error.reasons());
    }

    @Test
    void submitRequiresAnOfferForEverySku() {
        Product product = draft();
        catalog.offer(product.skus().get(0).id(), "50");
        var error = failure(products.submit(seller, product.id(), Optional.empty()), DomainRuleViolation.class);
        assertEquals(List.of("missing-offer:CAM-M"), error.reasons());
    }

    @Test
    void sellerListsOnlyOwnStoreAndPaginates() {
        Product a = draft();
        Product b = catalog.draft(sku("BONE", "P"));
        item(products.create(seller(UUID.randomUUID()), category, "BRL", "Alheio", "", Map.of(), List.of()));
        Page<Product> first = item(products.list(seller, Optional.empty(), Optional.of(UUID.randomUUID()),
                Optional.empty(), ProductSort.DEFAULT, Optional.empty(), 1));
        Page<Product> second = item(products.list(seller, Optional.empty(), Optional.empty(), Optional.empty(),
                ProductSort.DEFAULT, first.nextCursor(), 1));
        Set<UUID> seen = new HashSet<>();
        first.items().forEach(product -> seen.add(product.id()));
        second.items().forEach(product -> seen.add(product.id()));
        assertEquals(Set.of(a.id(), b.id()), seen);
        assertTrue(second.nextCursor().isEmpty());
        assertEquals(3,
                item(products.list(admin(), Optional.empty(), Optional.empty(), Optional.empty(), ProductSort.DEFAULT,
                        Optional.empty(), 10)).items().size());
    }

    @Test
    void inactiveCategoryBlocksCreation() {
        item(catalog.categories.revise(admin(), category, Optional.empty(), "Camisetas", List.of(BRAND, SIZE),
                false));
        var error = failure(products.create(seller, category, "BRL", "t", "", Map.of(), List.of()),
                DomainRuleViolation.class);
        assertEquals("category-inactive", error.code());
    }
}
