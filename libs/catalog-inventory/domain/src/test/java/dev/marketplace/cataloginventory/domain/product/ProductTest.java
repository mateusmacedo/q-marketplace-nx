package dev.marketplace.cataloginventory.domain.product;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.InvalidTransitionException;
import dev.marketplace.cataloginventory.domain.category.AttributeDefinition;
import dev.marketplace.cataloginventory.domain.category.AttributeType;
import dev.marketplace.cataloginventory.domain.category.Category;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        assertTrue(product.publishingViolations(ATTRS, false, offered(product)).contains("category-inactive"));
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
    void rejectionRequiresReasonAndReturnsToDraft() {
        Product product = complete();
        product.submit(ATTRS, true, offered(product), "seller-1", NOW);
        assertThrows(DomainRuleViolation.class, () -> product.reject(" ", "mod", NOW));
        product.reject("Fotos ausentes", "mod", NOW);
        assertEquals(DRAFT, product.status());
        assertEquals("Fotos ausentes", product.drainChanges().get(2).reason());
    }

    @Test
    void blockedProductIsHiddenAndFrozenUntilUnblocked() {
        Product product = active();
        assertTrue(product.isPubliclyVisible(SellerStatus.APPROVED));
        product.block("Produto proibido", "mod", NOW);
        assertFalse(product.isPubliclyVisible(SellerStatus.APPROVED));
        assertThrows(InvalidTransitionException.class,
                () -> product.reviseContent(T_SHIRTS, ATTRS, "x", "", Map.of("marca", "Acme"), "seller-1", NOW));
        assertThrows(InvalidTransitionException.class, () -> product.block("de novo", "mod", NOW));
        product.unblock("mod", NOW);
        assertEquals(PENDING_MODERATION, product.status());
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

        product.block("Produto proibido", "mod-1", NOW);
        List<ProductEvent> events = product.drainEvents();
        var blocking = (ProductEvent.ProductStatusChanged) events.get(0);
        assertEquals(BLOCKED, blocking.to());
        assertEquals("Produto proibido", blocking.reason());
        assertEquals("Produto proibido", ((ProductEvent.ProductBlocked) events.get(1)).reason());
        assertEquals(2, events.size());
    }
}
