package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.Reason;
import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import dev.marketplace.cataloginventory.domain.product.PublishingIssue;
import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static dev.marketplace.cataloginventory.application.Fixtures.*;
import static dev.marketplace.cataloginventory.application.ProductAction.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductActionsTest {
    final Catalog catalog = new Catalog();
    final ProductService products = catalog.products;
    final ModerationService moderation = catalog.moderation;
    final ProductActions actions = new ProductActions(catalog.store);
    final Actor seller = catalog.seller;

    ProductActions.Available available(Actor actor, Product product) {
        return ProductActions.of(actor, product, item(actions.eligibility(actor, List.of(product))),
                Optional.empty());
    }

    private int drafts;

    /** Rascunho com códigos de SKU novos, para vários produtos caberem na mesma loja. */
    Product draft() {
        drafts++;
        return catalog.draft(sku("CAM-P" + drafts, "P"), sku("CAM-M" + drafts, "M"));
    }

    Product submitted() {
        return item(products.submit(seller, catalog.offered(draft()).id(), Optional.empty()));
    }

    Product pendingIn(UUID store) {
        Actor owner = seller(store);
        Product product = item(products.create(owner, catalog.category, "BRL", "Camiseta", "",
                Map.of("marca", "Acme"), List.of(sku("CAM-P", "P"))));
        item(catalog.offers.define(owner, product.skus().getFirst().id(), Optional.empty(), price("50")));
        return item(products.submit(owner, product.id(), Optional.empty()));
    }

    static List<String> blockedKeys(ProductActions.Available available) {
        return available.blocked().stream().map(b -> b.action() + "=" + b.reason().key()).toList();
    }

    @Test
    void sellerActionsFollowTheStatusAndTheEditRules() {
        Product product = draft();
        ProductActions.Available inDraft = available(seller, product);
        assertEquals(List.of(EDIT, CHANGE_CATEGORY, ADD_SKU, REMOVE_SKU, EDIT_OFFER, EDIT_STOCK, SUBMIT, ARCHIVE),
                inDraft.allowed());
        assertTrue(inDraft.blocked().isEmpty());
        assertFalse(inDraft.saveReturnsToModeration());

        Product pending = item(products.submit(seller, catalog.offered(product).id(), Optional.empty()));
        ProductActions.Available inQueue = available(seller, pending);
        assertEquals(List.of(EDIT, CHANGE_CATEGORY, ADD_SKU, EDIT_OFFER, EDIT_STOCK, ARCHIVE), inQueue.allowed());
        assertEquals(List.of("REMOVE_SKU=sku-removal-requires-draft"), blockedKeys(inQueue));
        assertFalse(inQueue.saveReturnsToModeration(), "na fila, salvar não muda o status");

        catalog.approveSeller();
        Product active = item(moderation.approve(moderator(), pending.id(), Optional.empty()));
        ProductActions.Available published = available(seller, active);
        assertEquals(List.of(EDIT, CHANGE_CATEGORY, ADD_SKU, EDIT_OFFER, EDIT_STOCK, PAUSE, ARCHIVE),
                published.allowed());
        assertTrue(published.saveReturnsToModeration());

        Product paused = item(products.pause(seller, active.id(), Optional.empty()));
        assertEquals(List.of(EDIT, CHANGE_CATEGORY, ADD_SKU, EDIT_OFFER, EDIT_STOCK, RESUME, ARCHIVE),
                available(seller, paused).allowed());

        Product blocked = item(moderation.block(moderator(), paused.id(), Optional.empty(), List.of(),
                Optional.of("Produto proibido")));
        ProductActions.Available frozen = available(seller, blocked);
        assertEquals(List.of(EDIT_OFFER, EDIT_STOCK, ARCHIVE), frozen.allowed());
        assertTrue(frozen.blocked().isEmpty());
        assertFalse(frozen.saveReturnsToModeration());

        Product archived = item(products.archive(seller, blocked.id(), Optional.empty()));
        assertTrue(available(seller, archived).allowed().isEmpty());
    }

    @Test
    void addingSkuIsOfferedInEveryEditableStatusBecauseTheDomainAcceptsIt() {
        Product active = catalog.active(sku("CAM-P", "P"));
        assertTrue(available(seller, active).allows(ADD_SKU));
        Product revised = item(products.addSku(seller, active.id(), Optional.empty(), sku("CAM-G", "G")));
        assertEquals(ProductStatus.PENDING_MODERATION, revised.status());
    }

    @Test
    void resumeShowsTheEligibilityReasonInsteadOfDisappearing() {
        Product paused = item(products.pause(seller, catalog.active(sku("CAM-P", "P")).id(), Optional.empty()));
        item(catalog.eligibility.record(verdict(catalog.storeId, SellerStatus.REJECTED)));
        ProductActions.Available available = available(seller, paused);
        assertFalse(available.allows(RESUME));
        assertEquals(List.of("REMOVE_SKU=sku-removal-requires-draft", "RESUME=seller-not-eligible:REJECTED"),
                blockedKeys(available));
        ProductActions.Blocked resume = available.blocked().getLast();
        assertEquals("seller-not-eligible", resume.reason().code());
        assertEquals(List.of("REJECTED"), resume.reason().params());
        var refusal = failure(products.resume(seller, paused.id(), Optional.empty()), DomainRuleViolation.class);
        assertEquals(List.of(resume.reason()), refusal.details(), "o motivo é o mesmo da recusa");
    }

    @Test
    void moderatorDecidesTheQueueAndNeverEditsContent() {
        Product pending = submitted();
        ProductActions.Available unapproved = available(moderator(), pending);
        assertEquals(List.of(REJECT, BLOCK), unapproved.allowed());
        assertEquals(List.of("APPROVE=seller-not-eligible:PENDING"), blockedKeys(unapproved));

        catalog.approveSeller();
        assertEquals(List.of(APPROVE, REJECT, BLOCK), available(moderator(), pending).allowed());
        Product active = item(moderation.approve(moderator(), pending.id(), Optional.empty()));
        assertEquals(List.of(BLOCK), available(moderator(), active).allowed());
        Product blocked = item(moderation.block(moderator(), active.id(), Optional.empty(), List.of(),
                Optional.of("Produto proibido")));
        assertEquals(List.of(UNBLOCK), available(moderator(), blocked).allowed());
        assertFalse(available(moderator(), blocked).saveReturnsToModeration());
    }

    @Test
    void readersAndOtherStoresGetNoActions() {
        Product product = submitted();
        assertTrue(available(admin(), product).allowed().isEmpty());
        assertTrue(available(admin(), product).blocked().isEmpty());
        assertTrue(available(seller(UUID.randomUUID()), product).allowed().isEmpty());
    }

    @Test
    void knownPublishingIssuesBlockSubmissionAndApproval() {
        Product product = draft();
        List<PublishingIssue> issues = item(products.publishingIssues(product, Set.of())).orElseThrow();
        assertFalse(issues.isEmpty());
        ProductActions.Available incomplete = ProductActions.of(seller, product, Map.of(), Optional.of(issues));
        assertEquals(List.of("SUBMIT=product-not-publishable"), blockedKeys(incomplete));
        assertTrue(ProductActions.of(seller, product, Map.of(), Optional.of(List.of())).allows(SUBMIT));

        Product pending = submitted();
        Reason missing = issues.getFirst().reason();
        ProductActions.Available forModerator = ProductActions.of(moderator(), pending,
                Map.of(catalog.storeId, SellerStatus.PENDING), Optional.of(List.of(new PublishingIssue(missing, "skus[CAM-P].offer"))));
        assertEquals(List.of("APPROVE=product-not-publishable"), blockedKeys(forModerator),
                "como Product.approve, a completude vem antes da loja");
    }

    @Test
    void eligibilityIsReadOncePerPageAndOnlyForProductsThatDependOnIt() {
        List<Product> page = List.of(pendingIn(UUID.randomUUID()), pendingIn(UUID.randomUUID()),
                pendingIn(UUID.randomUUID()), draft());
        int before = catalog.store.statusQueries;
        Map<UUID, SellerStatus> eligibility = item(actions.eligibility(moderator(), page));
        assertEquals(before + 1, catalog.store.statusQueries, "uma consulta para a página inteira");
        assertEquals(Set.of(page.get(0).storeId(), page.get(1).storeId(), page.get(2).storeId()),
                eligibility.keySet(), "o rascunho não depende da loja");
        page.forEach(product -> ProductActions.of(moderator(), product, eligibility, Optional.empty()));

        item(actions.eligibility(seller, List.of(draft(), submitted())));
        assertEquals(before + 1, catalog.store.statusQueries, "nada do seller depende da loja fora da retomada");
    }
}
