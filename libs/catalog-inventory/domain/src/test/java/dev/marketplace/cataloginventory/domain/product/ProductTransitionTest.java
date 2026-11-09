package dev.marketplace.cataloginventory.domain.product;

import org.junit.jupiter.api.Test;
import java.util.EnumSet;
import java.util.Set;
import static dev.marketplace.cataloginventory.domain.product.ProductStatus.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductTransitionTest {
    private static Set<ProductStatus> originsOf(ProductTransition transition) {
        Set<ProductStatus> origins = EnumSet.noneOf(ProductStatus.class);
        for (ProductStatus status : ProductStatus.values())
            if (transition.acceptsFrom(status))
                origins.add(status);
        return origins;
    }

    @Test
    void everyTransitionHasItsFixedTarget() {
        assertEquals(DRAFT, ProductTransition.CREATE.to());
        assertEquals(PENDING_MODERATION, ProductTransition.SUBMIT.to());
        assertEquals(ACTIVE, ProductTransition.APPROVE.to());
        assertEquals(DRAFT, ProductTransition.REJECT.to());
        assertEquals(BLOCKED, ProductTransition.BLOCK.to());
        assertEquals(PENDING_MODERATION, ProductTransition.UNBLOCK.to());
        assertEquals(PAUSED_BY_SELLER, ProductTransition.PAUSE.to());
        assertEquals(ACTIVE, ProductTransition.RESUME.to());
        assertEquals(ARCHIVED, ProductTransition.ARCHIVE.to());
        assertEquals(PENDING_MODERATION, ProductTransition.REVIEW_REQUIRED.to());
    }

    @Test
    void storeDoesTheLifecycleAndModerationDecides() {
        Set<ProductTransition> store = EnumSet.noneOf(ProductTransition.class);
        for (ProductTransition transition : ProductTransition.values())
            if (transition.side() == TransitionSide.STORE)
                store.add(transition);
        assertEquals(EnumSet.of(ProductTransition.CREATE, ProductTransition.SUBMIT, ProductTransition.PAUSE,
                ProductTransition.RESUME, ProductTransition.ARCHIVE, ProductTransition.REVIEW_REQUIRED), store);
        assertEquals(TransitionSide.MODERATION, ProductTransition.APPROVE.side());
        assertEquals(TransitionSide.MODERATION, ProductTransition.REJECT.side());
        assertEquals(TransitionSide.MODERATION, ProductTransition.BLOCK.side());
        assertEquals(TransitionSide.MODERATION, ProductTransition.UNBLOCK.side());
    }

    @Test
    void everyTransitionAcceptsOnlyItsOrigins() {
        assertEquals(Set.of(), originsOf(ProductTransition.CREATE));
        assertEquals(Set.of(DRAFT), originsOf(ProductTransition.SUBMIT));
        assertEquals(Set.of(PENDING_MODERATION), originsOf(ProductTransition.APPROVE));
        assertEquals(Set.of(PENDING_MODERATION), originsOf(ProductTransition.REJECT));
        assertEquals(Set.of(DRAFT, PENDING_MODERATION, ACTIVE, PAUSED_BY_SELLER, OUT_OF_STOCK),
                originsOf(ProductTransition.BLOCK));
        assertEquals(Set.of(BLOCKED), originsOf(ProductTransition.UNBLOCK));
        assertEquals(Set.of(ACTIVE), originsOf(ProductTransition.PAUSE));
        assertEquals(Set.of(PAUSED_BY_SELLER), originsOf(ProductTransition.RESUME));
        assertEquals(Set.of(DRAFT, PENDING_MODERATION, ACTIVE, PAUSED_BY_SELLER, OUT_OF_STOCK, BLOCKED),
                originsOf(ProductTransition.ARCHIVE));
        assertEquals(Set.of(ACTIVE, PAUSED_BY_SELLER), originsOf(ProductTransition.REVIEW_REQUIRED));
    }
}
