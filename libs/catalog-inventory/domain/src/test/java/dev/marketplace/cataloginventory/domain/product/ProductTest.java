package dev.marketplace.cataloginventory.domain.product;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.InvalidTransitionException;
import dev.marketplace.cataloginventory.domain.Reason;
import dev.marketplace.cataloginventory.domain.category.AttributeDefinition;
import dev.marketplace.cataloginventory.domain.category.AttributeType;
import dev.marketplace.cataloginventory.domain.category.Category;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import static dev.marketplace.cataloginventory.domain.product.ProductStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductTest {
    static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    static final UUID TENANT = UUID.randomUUID();
    static final AttributeDefinition BRAND = new AttributeDefinition("marca", "Marca", AttributeType.TEXT, true, false,
            List.of());
    static final AttributeDefinition SIZE = new AttributeDefinition("tamanho", "Tamanho", AttributeType.OPTION, true,
            true, List.of("P", "M", "G"));
    static final List<AttributeDefinition> ATTRS = List.of(BRAND, SIZE);
    static final Category T_SHIRTS = Category.root(UUID.randomUUID(), TENANT, "camisetas", "Camisetas", ATTRS);
    static final Dimensions BOX = new Dimensions(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 300);

    static Sku sku(String code, String size) {
        return Sku.create(UUID.randomUUID(), code, Map.of("tamanho", size), true, BOX);
    }

    /** Motivo só com o complemento livre, sem motivos prontos. */
    static DecisionReason because(ProductTransition decision, String note) {
        return DecisionReason.of(decision, List.of(), List.of(), Optional.of(note));
    }

    static Product draft(Map<String, String> attributes, Sku... skus) {
        return Product.draft(UUID.randomUUID(), UUID.randomUUID(), T_SHIRTS, ATTRS, "BRL", "Camiseta básica",
                "Algodão",
                attributes, List.of(skus), "seller-1", NOW);
    }

    static Set<UUID> offered(Product product) {
        return product.skus().stream().map(Sku::id).collect(Collectors.toSet());
    }

    static Product complete() {
        return draft(Map.of("marca", "Acme"), sku("CAM-P", "P"), sku("CAM-M", "M"));
    }

    static Product active() {
        Product product = complete();
        product.submit(ATTRS, true, offered(product), "seller-1", NOW);
        product.approve(ATTRS, true, offered(product), SellerStatus.APPROVED, "mod-1", NOW);
        return product;
    }

    @Test
    void happyPathFromDraftToActiveRecordsHistory() {
        Product product = active();
        assertEquals(ACTIVE, product.status());
        assertEquals("BRL", product.currency());
        assertEquals(List.of(ProductTransition.CREATE, ProductTransition.SUBMIT, ProductTransition.APPROVE),
                product.drainChanges().stream().map(StatusChange::transition).toList());
        assertTrue(product.drainChanges().isEmpty());
    }

    @Test
    void submitListsEveryMissingRequirementIncludingOffers() {
        Sku withoutVariant = Sku.create(UUID.randomUUID(), "CAM-X", Map.of(), true, BOX);
        Product product = draft(Map.of(), sku("CAM-P", "P"), withoutVariant);
        var error = assertThrows(DomainRuleViolation.class,
                () -> product.submit(ATTRS, true, Set.of(product.skus().get(0).id()), "seller-1", NOW));
        assertEquals("product-not-publishable", error.code());
        assertEquals(
                List.of("missing-offer:CAM-X", "missing-attribute:marca", "missing-variant-attribute:CAM-X:tamanho"),
                error.reasons());
        assertEquals(Arrays.asList(null, "Preencha o atributo Marca.", "Preencha Tamanho no SKU CAM-X."),
                error.details().stream().map(Reason::message).toList());
        assertEquals(List.of("CAM-X", "tamanho"), error.details().get(2).params());
        assertEquals(DRAFT, product.status());
    }

    @Test
    void submitRequiresSkusDistinctVariantsAndActiveCategory() {
        Product empty = draft(Map.of("marca", "Acme"));
        assertEquals(List.of("no-sku"),
                assertThrows(DomainRuleViolation.class, () -> empty.submit(ATTRS, true, Set.of(), "s", NOW)).reasons());
        Product duplicated = draft(Map.of("marca", "Acme"), sku("CAM-1", "P"), sku("CAM-2", "P"));
        assertEquals(List.of("duplicate-variant:CAM-2"), assertThrows(DomainRuleViolation.class,
                () -> duplicated.submit(ATTRS, true, offered(duplicated), "s", NOW)).reasons());
        Product product = complete();
        assertTrue(product.publishingViolations(ATTRS, false, offered(product)).stream().map(Reason::key)
                .anyMatch("category-inactive"::equals));
    }

    @Test
    void currencyMustBeIso4217() {
        assertThrows(DomainRuleViolation.class,
                () -> Product.draft(UUID.randomUUID(), UUID.randomUUID(), T_SHIRTS, ATTRS, "real",
                        "t", "", Map.of("marca", "Acme"), List.of(), "s", NOW));
    }

    @Test
    void rejectsUnknownOrInvalidAttributeValuesEvenInDraft() {
        var error = assertThrows(DomainRuleViolation.class,
                () -> draft(Map.of("marca", "Acme", "voltagem", "220"), sku("A", "XG")));
        assertEquals("invalid-attributes", error.code());
        assertEquals(List.of("unknown-attribute:voltagem", "invalid-attribute:A:tamanho"), error.reasons());
        assertEquals("O valor de Tamanho no SKU A não é aceito pela categoria.", error.details().get(1).message());
    }

    @Test
    void onlyApprovedSellerCanBeActivated() {
        Product product = complete();
        product.submit(ATTRS, true, offered(product), "seller-1", NOW);
        var error = assertThrows(DomainRuleViolation.class,
                () -> product.approve(ATTRS, true, offered(product), SellerStatus.REJECTED, "mod", NOW));
        assertEquals("seller-not-eligible", error.code());
        assertEquals(PENDING_MODERATION, product.status());
    }

    @Test
    void contentChangeOfPublishedProductReturnsToModeration() {
        Product product = active();
        product.reviseContent(T_SHIRTS, ATTRS, "Camiseta premium", "Algodão pima", Map.of("marca", "Acme"), "seller-1",
                NOW);
        assertEquals(PENDING_MODERATION, product.status());
        assertEquals(ProductTransition.REVIEW_REQUIRED, product.drainChanges().get(3).transition());
    }

    @Test
    void rejectionRecordsReasonTextAndCodesAndReturnsToDraft() {
        Product product = complete();
        product.submit(ATTRS, true, offered(product), "seller-1", NOW);
        product.reject(DecisionReason.of(ProductTransition.REJECT, DecisionReasonTest.CATALOG,
                List.of("wrong-category"), Optional.of("Fotos ausentes")), "mod", NOW);
        assertEquals(DRAFT, product.status());
        StatusChange rejection = product.drainChanges().get(2);
        assertEquals(List.of(new ReasonLabel("wrong-category", "Categoria incorreta")), rejection.reasons());
        assertEquals("Fotos ausentes", rejection.note());
        assertEquals("Categoria incorreta. Fotos ausentes", rejection.reason());
        assertEquals(List.of("wrong-category"), rejection.reasonCodes());
        assertThrows(IllegalArgumentException.class,
                () -> product.block(because(ProductTransition.REJECT, "motivo de outra decisão"), "mod", NOW));
    }

    @Test
    void blockedProductIsHiddenAndFrozenUntilUnblocked() {
        Product product = active();
        assertTrue(product.isPubliclyVisible(SellerStatus.APPROVED));
        product.block(because(ProductTransition.BLOCK, "Produto proibido"), "mod", NOW);
        assertFalse(product.isPubliclyVisible(SellerStatus.APPROVED));
        assertThrows(InvalidTransitionException.class,
                () -> product.reviseContent(T_SHIRTS, ATTRS, "x", "", Map.of("marca", "Acme"), "seller-1", NOW));
        assertThrows(InvalidTransitionException.class,
                () -> product.block(because(ProductTransition.BLOCK, "de novo"), "mod", NOW));
        product.unblock("mod", NOW);
        assertEquals(PENDING_MODERATION, product.status());
    }

    @Test
    void moderationQueueEntryIsMarkedOnEveryArrivalAndKeptWhileWaiting() {
        Product product = complete();
        assertTrue(product.moderationQueuedAt().isEmpty(), "rascunho fora da fila");
        product.submit(ATTRS, true, offered(product), "seller-1", NOW);
        assertEquals(NOW, product.moderationQueuedAt().orElseThrow());
        product.reviseContent(T_SHIRTS, ATTRS, "Camiseta nova", "", Map.of("marca", "Acme"), "seller-1",
                NOW.plusSeconds(60));
        assertEquals(NOW, product.moderationQueuedAt().orElseThrow(), "editar na fila não muda a chegada");
        product.approve(ATTRS, true, offered(product), SellerStatus.APPROVED, "mod", NOW.plusSeconds(120));
        assertTrue(product.moderationQueuedAt().isEmpty(), "fora da fila, sem chegada");
        product.reviseContent(T_SHIRTS, ATTRS, "Camiseta revista", "", Map.of("marca", "Acme"), "seller-1",
                NOW.plusSeconds(180));
        assertEquals(NOW.plusSeconds(180), product.moderationQueuedAt().orElseThrow(), "volta à moderação");
        product.block(because(ProductTransition.BLOCK, "Produto proibido"), "mod", NOW.plusSeconds(240));
        assertTrue(product.moderationQueuedAt().isEmpty());
        product.unblock("mod", NOW.plusSeconds(300));
        assertEquals(NOW.plusSeconds(300), product.moderationQueuedAt().orElseThrow(), "desbloqueio volta à fila");
    }

    @Test
    void pauseResumeAndArchiveFreezesOfferAndStock() {
        Product product = active();
        product.pause("seller-1", NOW);
        assertFalse(product.isPubliclyVisible(SellerStatus.APPROVED));
        assertThrows(DomainRuleViolation.class, () -> product.resume(SellerStatus.REJECTED, "seller-1", NOW));
        product.resume(SellerStatus.APPROVED, "seller-1", NOW);
        assertDoesNotThrow(() -> product.requireNotArchived("CHANGE_PRICE"));
        product.archive("seller-1", NOW);
        assertEquals(ARCHIVED, product.status());
        assertThrows(InvalidTransitionException.class, () -> product.archive("seller-1", NOW));
        assertThrows(InvalidTransitionException.class, () -> product.requireNotArchived("CHANGE_PRICE"));
    }

    @Test
    void rejectedSellerHidesActiveProduct() {
        assertFalse(active().isPubliclyVisible(SellerStatus.REJECTED));
    }

    @Test
    void outOfStockIsDerivedFromBalance() {
        Product product = active();
        assertEquals(OUT_OF_STOCK, product.effectiveStatus(false));
        assertEquals(ACTIVE, product.effectiveStatus(true));
        product.pause("seller-1", NOW);
        assertEquals(PAUSED_BY_SELLER, product.effectiveStatus(false));
    }

    @Test
    void invalidTransitionsAreRefused() {
        Product product = complete();
        assertThrows(InvalidTransitionException.class,
                () -> product.approve(ATTRS, true, offered(product), SellerStatus.APPROVED, "mod", NOW));
        assertThrows(InvalidTransitionException.class, () -> product.pause("seller-1", NOW));
        assertThrows(InvalidTransitionException.class, () -> product.unblock("mod", NOW));
    }

    @Test
    void skuRulesInsideTheAggregate() {
        Product product = complete();
        assertEquals("duplicate-seller-sku",
                assertThrows(DomainRuleViolation.class, () -> product.addSku(sku("CAM-P", "G"), ATTRS, "s", NOW))
                        .code());
        product.removeSku(product.skus().get(0).id(), NOW);
        assertEquals(1, product.skus().size());
        Product published = active();
        assertEquals("sku-removal-requires-draft",
                assertThrows(DomainRuleViolation.class, () -> published.removeSku(published.skus().get(0).id(), NOW))
                        .code());
    }

    @Test
    void editRulesFollowTheStatus() {
        Product draft = complete();
        assertTrue(draft.acceptsEdit());
        assertFalse(draft.editReturnsToModeration());
        assertTrue(draft.skuRemovalRefusal().isEmpty());
        Product published = active();
        assertTrue(published.acceptsEdit());
        assertTrue(published.editReturnsToModeration(), "editar o publicado devolve à moderação");
        assertEquals("sku-removal-requires-draft", published.skuRemovalRefusal().orElseThrow().code());
        assertTrue(published.skuAdditionRefusal().isEmpty(), "SKU novo vale em todo estado editável");
        published.pause("seller-1", NOW);
        assertTrue(published.editReturnsToModeration());
        published.block(because(ProductTransition.BLOCK, "Produto proibido"), "mod", NOW);
        assertFalse(published.acceptsEdit());
        assertTrue(published.acceptsOfferAndStockChanges(), "bloqueado ainda ajusta preço e estoque");
        published.archive("seller-1", NOW);
        assertFalse(published.acceptsOfferAndStockChanges());
    }

    @Test
    void skuLimitRefusesTheNextSkuWithTheSameReasonAsTheWrite() {
        Sku[] skus = new Sku[Product.MAX_SKUS];
        for (int i = 0; i < skus.length; i++)
            skus[i] = Sku.create(UUID.randomUUID(), "SKU-" + i, Map.of(), false, null);
        Product full = draft(Map.of("marca", "Acme"), skus);
        Reason refusal = full.skuAdditionRefusal().orElseThrow();
        var error = assertThrows(DomainRuleViolation.class,
                () -> full.addSku(Sku.create(UUID.randomUUID(), "SKU-X", Map.of(), false, null), List.of(), "s",
                        NOW));
        assertEquals(error.reason(), refusal);
        assertEquals("invalid-value", refusal.code());
        assertEquals("Produto aceita até 100 SKUs", refusal.message());
    }

    @Test
    void ineligibleStoreIsTheReasonApprovalAndResumeRefuse() {
        assertTrue(Product.ineligibility(SellerStatus.APPROVED).isEmpty());
        Reason pending = Product.ineligibility(SellerStatus.PENDING).orElseThrow();
        assertEquals("seller-not-eligible:PENDING", pending.key());
        Product product = complete();
        product.submit(ATTRS, true, offered(product), "seller-1", NOW);
        var error = assertThrows(DomainRuleViolation.class,
                () -> product.approve(ATTRS, true, offered(product), SellerStatus.PENDING, "mod", NOW));
        assertEquals(List.of(pending), error.details());
    }

    @Test
    void publishingIssuesPointToTheFieldThatResolvesThem() {
        Sku withoutVariant = Sku.create(UUID.randomUUID(), "CAM-X", Map.of(), true, BOX);
        Sku repeated = sku("CAM-X.2", "P");
        Product product = draft(Map.of(), sku("CAM-P", "P"), withoutVariant, repeated);
        Set<UUID> offered = Set.of(product.skus().get(0).id(), repeated.id());
        List<PublishingIssue> issues = product.publishingIssues(ATTRS, false, offered);
        assertEquals(product.publishingViolations(ATTRS, false, offered),
                issues.stream().map(PublishingIssue::reason).toList(), "mesmos motivos, na mesma ordem");
        assertEquals(List.of("categoryId", "skus[CAM-X].offer", "attributes.marca",
                "skus[CAM-X].variantAttributes.tamanho", "skus[CAM-X.2].variantAttributes"),
                issues.stream().map(PublishingIssue::field).toList());
        assertEquals("skus", draft(Map.of("marca", "Acme")).publishingIssues(ATTRS, true, Set.of()).getFirst()
                .field());
    }

    @Test
    void draftIssuesUseTheUnsavedAttributesWithoutChangingTheProduct() {
        Product product = complete();
        long version = product.version();
        List<PublishingIssue> issues = product.draftIssues(Map.of("marca", "Acme", "voltagem", "220"), ATTRS, true,
                offered(product));
        assertEquals(List.of("unknown-attribute:voltagem"),
                issues.stream().map(issue -> issue.reason().key()).toList());
        assertEquals("attributes.voltagem", issues.getFirst().field());
        assertEquals(Map.of("marca", "Acme"), product.attributes());
        assertEquals(version, product.version());
        assertTrue(product.draftIssues(Map.of(), ATTRS, true, offered(product)).stream()
                .anyMatch(issue -> issue.reason().key().equals("missing-attribute:marca")));
        product.block(because(ProductTransition.BLOCK, "Produto proibido"), "mod", NOW);
        assertThrows(InvalidTransitionException.class,
                () -> product.draftIssues(Map.of("marca", "Acme"), ATTRS, true, offered(product)));
    }

    @Test
    void addingSkuToPublishedProductRequiresReview() {
        Product product = active();
        product.addSku(sku("CAM-G", "G"), ATTRS, "seller-1", NOW);
        assertEquals(PENDING_MODERATION, product.status());
        assertEquals(3, product.skus().size());
    }

    @Test
    void inactiveCategoryCannotReceiveNewProducts() {
        Category inactive = Category.root(UUID.randomUUID(), TENANT, "inativa", "Inativa", ATTRS);
        inactive.deactivate();
        assertEquals("category-inactive", assertThrows(DomainRuleViolation.class, () -> Product.draft(UUID.randomUUID(),
                UUID.randomUUID(), inactive, ATTRS, "BRL", "t", "", Map.of(), List.of(), "s", NOW)).code());
    }

    @Test
    void creationTransitionsAndBlockAreRecordedAsEvents() {
        Product product = active();
        List<ProductEvent> lifecycle = product.drainEvents();
        var created = (ProductEvent.ProductCreated) lifecycle.get(0);
        assertEquals(product.id(), created.productId());
        assertEquals("BRL", created.currency());
        assertEquals(List.of("CAM-P", "CAM-M"),
                created.skus().stream().map(ProductEvent.SkuSummary::sellerSku).toList());
        var approved = (ProductEvent.ProductStatusChanged) lifecycle.get(2);
        assertEquals(ProductTransition.APPROVE, approved.transition());
        assertEquals(PENDING_MODERATION, approved.from());
        assertEquals(ACTIVE, approved.to());
        assertEquals(List.of(ProductTransition.SUBMIT, ProductTransition.APPROVE), lifecycle.stream()
                .filter(ProductEvent.ProductStatusChanged.class::isInstance)
                .map(e -> ((ProductEvent.ProductStatusChanged) e).transition()).toList(),
                "a criação não repete como transição");
        assertTrue(product.drainEvents().isEmpty(), "drenar esvazia os eventos pendentes");

        product.block(DecisionReason.of(ProductTransition.BLOCK, DecisionReasonTest.CATALOG,
                List.of("prohibited-item"), Optional.of("Réplica de marca")), "mod-1", NOW);
        List<ProductEvent> events = product.drainEvents();
        var blocking = (ProductEvent.ProductStatusChanged) events.get(0);
        assertEquals(BLOCKED, blocking.to());
        assertEquals("Item proibido na plataforma. Réplica de marca", blocking.reason());
        assertEquals(List.of("prohibited-item"), blocking.reasonCodes());
        var blocked = (ProductEvent.ProductBlocked) events.get(1);
        assertEquals("Item proibido na plataforma. Réplica de marca", blocked.reason());
        assertEquals(List.of("prohibited-item"), blocked.reasonCodes());
        assertEquals(List.of(new ReasonLabel("prohibited-item", "Item proibido na plataforma")), blocked.reasons());
        assertEquals("Réplica de marca", blocked.note());
        assertEquals(2, events.size());
    }
}
