package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.InvalidTransitionException;
import dev.marketplace.cataloginventory.domain.Reason;
import dev.marketplace.cataloginventory.domain.category.AttributeDefinition;
import dev.marketplace.cataloginventory.domain.category.AttributeType;
import dev.marketplace.cataloginventory.domain.product.CategoryChange;
import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.PublishingIssue;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static dev.marketplace.cataloginventory.application.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductCheckTest {
    static final AttributeDefinition WAIST = new AttributeDefinition("cintura", "Cintura (cm)", AttributeType.INTEGER,
            true, true, List.of());
    final Catalog catalog = new Catalog();
    final ProductService products = catalog.products;
    final Actor seller = catalog.seller;

    Product offeredDraft() {
        return catalog.offered(catalog.draft(sku("CAM-P", "P"), sku("CAM-M", "M")));
    }

    static List<String> keys(List<PublishingIssue> issues) {
        return issues.stream().map(issue -> issue.reason().key()).toList();
    }

    @Test
    void savedProductIssuesComeWithTheFieldAndOnlyWhereEditingApplies() {
        Product product = catalog.draft(sku("CAM-P", "P"));
        List<PublishingIssue> issues = item(products.publishingIssues(product, Set.of())).orElseThrow();
        assertEquals(List.of("missing-offer:CAM-P"), keys(issues));
        assertEquals("skus[CAM-P].offer", issues.getFirst().field());
        Product blocked = item(catalog.moderation.block(moderator(), product.id(), Optional.empty(), List.of(),
                Optional.of("Produto proibido")));
        assertTrue(item(products.publishingIssues(blocked, Set.of())).isEmpty());
    }

    @Test
    void checkEvaluatesTheUnsavedDraftWithoutWriting() {
        Product product = offeredDraft();
        int history = item(products.history(seller, product.id())).size();
        ProductService.ProductCheck check = item(products.check(seller, product.id(), catalog.category,
                Map.of("cor", "azul")));
        assertEquals(List.of("unknown-attribute:cor", "missing-attribute:marca"), keys(check.publishingIssues()));
        assertEquals(List.of("attributes.cor", "attributes.marca"),
                check.publishingIssues().stream().map(PublishingIssue::field).toList());
        assertTrue(check.categoryChange().isEmpty(), "a categoria não mudou");
        Product stored = item(products.get(seller, product.id()));
        assertEquals(Map.of("marca", "Acme"), stored.attributes());
        assertEquals(product.version(), stored.version());
        assertEquals(history, item(products.history(seller, product.id())).size());
        assertTrue(item(products.check(seller, product.id(), catalog.category, Map.of("marca", "Acme")))
                .publishingIssues().isEmpty());
    }

    @Test
    void checkWithAnotherCategoryReportsTheEffectOfTheChange() {
        Product product = offeredDraft();
        UUID pants = item(catalog.categories.create(admin(), Optional.empty(), "calcas", "Calças",
                List.of(BRAND, WAIST))).category().id();
        ProductService.ProductCheck check = item(products.check(seller, product.id(), pants,
                Map.of("marca", "Acme")));
        CategoryChange change = check.categoryChange().orElseThrow();
        assertTrue(change.removedAttributes().isEmpty());
        assertEquals(List.of(WAIST), change.newRequired());
        assertEquals(List.of("CAM-P", "CAM-M"),
                change.blockingSkus().stream().map(CategoryChange.BlockingSku::sellerSku).toList());
        assertEquals(List.of("unknown-attribute:CAM-P:tamanho"),
                change.blockingSkus().getFirst().reasons().stream().map(Reason::key).toList());
        assertTrue(keys(check.publishingIssues()).contains("missing-variant-attribute:CAM-P:cintura"));
        assertEquals(catalog.category, item(products.get(seller, product.id())).categoryId(), "nada foi gravado");
    }

    @Test
    void checkHasTheSameReachAsTheEdit() {
        Product product = offeredDraft();
        failure(products.check(seller(UUID.randomUUID()), product.id(), catalog.category, Map.of()),
                NotFoundException.class);
        failure(products.check(moderator(), product.id(), catalog.category, Map.of()), AccessDeniedException.class);
        failure(products.check(seller, product.id(), UUID.randomUUID(), Map.of()), NotFoundException.class);
        item(catalog.moderation.block(moderator(), product.id(), Optional.empty(), List.of(),
                Optional.of("Produto proibido")));
        failure(products.check(seller, product.id(), catalog.category, Map.of("marca", "Acme")),
                InvalidTransitionException.class);
    }
}
