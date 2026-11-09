package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.category.AttributeDefinition;
import dev.marketplace.cataloginventory.domain.category.AttributeType;
import dev.marketplace.cataloginventory.domain.category.Category;
import dev.marketplace.cataloginventory.domain.category.CategoryNotEmptyException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.marketplace.cataloginventory.application.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class CategoryServiceTest {
    final InMemoryStore store = new InMemoryStore();
    final CategoryService service = new CategoryService(store);

    CategoryService.CategoryView root(String code, AttributeDefinition... attributes) {
        return item(service.create(admin(), Optional.empty(), code, "Nome", List.of(attributes)));
    }

    @Test
    void childViewIncludesInheritedAttributes() {
        UUID fashion = root("moda", BRAND).category().id();
        var tShirts = item(service.create(admin(), Optional.of(fashion), "camisetas", "Camisetas", List.of(SIZE)));
        assertEquals(List.of("marca", "tamanho"),
                tShirts.effectiveAttributes().stream().map(AttributeDefinition::code).toList());
        assertEquals(Optional.of(fashion), tShirts.category().parentId());
        assertTrue(tShirts.usable());
    }

    @Test
    void onlyTaxonomyManagersWrite() {
        failure(service.create(seller(UUID.randomUUID()), Optional.empty(), "moda", "Moda", List.of()),
                AccessDeniedException.class);
        failure(service.create(moderator(), Optional.empty(), "moda", "Moda", List.of()), AccessDeniedException.class);
    }

    @Test
    void codeIsUniquePerTenantAndParentMustExist() {
        root("moda");
        failure(service.create(admin(), Optional.empty(), "moda", "Outra", List.of()), DuplicateException.class);
        failure(service.create(admin(), Optional.of(UUID.randomUUID()), "x", "X", List.of()), NotFoundException.class);
    }

    @Test
    void revisionHonorsIfMatchAndDescendants() {
        UUID fashion = root("moda").category().id();
        item(service.create(admin(), Optional.of(fashion), "camisetas", "Camisetas", List.of(SIZE)));
        failure(service.revise(admin(), fashion, Optional.of(7L), "Moda", List.of(), true),
                VersionMismatchException.class);
        var conflict = failure(service.revise(admin(), fashion, Optional.of(0L), "Moda", List.of(SIZE), true),
                DomainRuleViolation.class);
        assertEquals("attribute-conflict", conflict.code());
        var revised = item(
                service.revise(admin(), fashion, Optional.of(0L), "Moda e acessórios", List.of(BRAND), true));
        assertEquals(1, revised.category().version());
        assertEquals("Moda e acessórios", revised.category().name());
    }

    @Test
    void inactiveAncestorMakesDescendantUnusable() {
        UUID fashion = root("moda").category().id();
        UUID tShirts = item(service.create(admin(), Optional.of(fashion), "camisetas", "Camisetas", List.of()))
                .category().id();
        item(service.revise(admin(), fashion, Optional.empty(), "Moda", List.of(), false));
        assertFalse(item(service.get(seller(UUID.randomUUID()), tShirts)).usable());
    }

    @Test
    void childrenArePaginatedByCode() {
        for (String code : List.of("c", "a", "b"))
            root(code);
        Page<CategoryNode> first = item(service.children(admin(), Optional.empty(), Optional.empty(), 2));
        assertEquals(List.of("a", "b"), first.items().stream().map(node -> node.category().code()).toList());
        Page<CategoryNode> second = item(service.children(admin(), Optional.empty(), first.nextCursor(), 2));
        assertEquals(List.of("c"), second.items().stream().map(node -> node.category().code()).toList());
        assertTrue(second.nextCursor().isEmpty());
    }

    @Test
    void viewsCarryPathLevelAndChildCount() {
        UUID fashion = root("moda").category().id();
        UUID tShirts = child(fashion, "camisetas");
        child(tShirts, "regatas");
        child(tShirts, "polos");
        var view = item(service.get(seller(UUID.randomUUID()), tShirts));
        assertEquals(List.of("moda"), view.ancestors().stream().map(Category::code).toList());
        assertEquals(2, view.level());
        assertEquals(2, view.childCount());
        CategoryNode node = item(service.children(admin(), Optional.of(fashion), Optional.empty(), 10)).items()
                .getFirst();
        assertEquals(List.of("moda"), node.ancestors().stream().map(CategoryNode.Ancestor::code).toList());
        assertEquals(2, node.level());
        assertEquals(2, node.childCount());
        assertEquals(1, item(service.get(admin(), fashion)).childCount());
        assertEquals(1, item(service.revise(admin(), fashion, Optional.of(0L), "Moda", List.of(), true)).childCount());
    }

    @Test
    void treeListsWholeTenantInTreeOrderByCursor() {
        UUID a = root("a").category().id();
        root("a-b");
        UUID x = child(a, "x");
        child(x, "y");
        Page<CategoryNode> first = item(service.tree(admin(), Optional.empty(), false, 2));
        assertEquals(List.of("a", "x"), first.items().stream().map(n -> n.category().code()).toList());
        assertEquals(Optional.of("a x"), first.nextCursor());
        Page<CategoryNode> second = item(service.tree(admin(), first.nextCursor(), false, 2));
        assertEquals(List.of("y", "a-b"), second.items().stream().map(n -> n.category().code()).toList());
        assertEquals(List.of("a", "x"),
                second.items().getFirst().ancestors().stream().map(CategoryNode.Ancestor::code).toList());
        assertEquals(3, second.items().getFirst().level());

        item(service.revise(admin(), a, Optional.of(0L), "A", List.of(), false));
        Page<CategoryNode> usable = item(service.tree(admin(), Optional.empty(), true, 10));
        assertEquals(List.of("a-b"), usable.items().stream().map(n -> n.category().code()).toList());
        failure(service.tree(admin(), Optional.of("A B"), false, 10), InvalidInputException.class);
        assertEquals(4, item(service.tree(admin(), Optional.empty(), false, CategoryService.TREE_PAGE_SIZE)).items()
                .size());
        failure(service.tree(admin(), Optional.empty(), false, CategoryService.TREE_PAGE_SIZE + 1),
                InvalidInputException.class);
        failure(service.children(admin(), Optional.empty(), Optional.empty(), Page.MAX_SIZE + 1),
                InvalidInputException.class);
    }

    @Test
    void byIdsReadsTheTenantCategoriesInCodeOrder() {
        UUID fashion = root("moda").category().id();
        UUID tShirts = child(fashion, "camisetas");
        child(tShirts, "regatas");
        UUID home = root("casa").category().id();
        UUID foreign = item(service.create(
                new Actor(UUID.randomUUID(), Optional.empty(), "x", Set.of(Capability.MANAGE_TAXONOMY)),
                Optional.empty(), "outra", "Outra", List.of())).category().id();
        List<CategoryNode> found = item(service.byIds(seller(UUID.randomUUID()),
                List.of(tShirts, home, UUID.randomUUID(), foreign, tShirts)));
        assertEquals(List.of("camisetas", "casa"), found.stream().map(n -> n.category().code()).toList());
        assertEquals(List.of("moda"), found.getFirst().ancestors().stream().map(CategoryNode.Ancestor::code).toList());
        assertEquals(1, found.getFirst().childCount());
        assertEquals(1, found.getLast().level());
        failure(service.byIds(admin(), List.of()), InvalidInputException.class);
        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i <= CategoryService.MAX_IDS; i++)
            tooMany.add(UUID.randomUUID());
        failure(service.byIds(admin(), tooMany), InvalidInputException.class);
    }

    @Test
    void deletesOnlyEmptyCategory() {
        Catalog catalog = new Catalog();
        UUID tShirts = catalog.category;
        UUID tanks = item(catalog.categories.create(admin(), Optional.of(tShirts), "regatas", "Regatas", List.of()))
                .category().id();
        var withChild = failure(catalog.categories.delete(admin(), tShirts, Optional.of(0L)),
                CategoryNotEmptyException.class);
        assertEquals(CategoryNotEmptyException.HAS_CHILDREN, withChild.code());
        failure(catalog.categories.delete(admin(), tanks, Optional.of(3L)), VersionMismatchException.class);
        failure(catalog.categories.delete(seller(catalog.storeId), tanks, Optional.of(0L)),
                AccessDeniedException.class);
        item(catalog.categories.delete(admin(), tanks, Optional.of(0L)));
        failure(catalog.categories.get(admin(), tanks), NotFoundException.class);

        var product = catalog.draft(sku("CAM-P", "P"));
        item(catalog.products.archive(catalog.seller, product.id(), Optional.empty()));
        var withProduct = failure(catalog.categories.delete(admin(), tShirts, Optional.empty()),
                CategoryNotEmptyException.class);
        assertEquals(CategoryNotEmptyException.HAS_PRODUCTS, withProduct.code());
    }

    @Test
    void batchDeletesBranchFromChildrenUpAndKeepsRequestOrder() {
        UUID fashion = root("moda").category().id();
        UUID tShirts = child(fashion, "camisetas");
        UUID tanks = child(tShirts, "regatas");
        UUID kept = root("casa").category().id();
        var outcomes = item(service.batch(admin(), CategoryBatchAction.DELETE,
                List.of(new Batch.Item(fashion, Optional.of(0L)), new Batch.Item(tanks, Optional.of(0L)),
                        new Batch.Item(kept, Optional.empty()), new Batch.Item(tShirts, Optional.of(0L)))));
        assertEquals(List.of(fashion, tanks, kept, tShirts), outcomes.stream().map(Batch.Outcome::id).toList());
        assertTrue(outcomes.get(0).value().isPresent());
        assertTrue(outcomes.get(1).value().isPresent());
        assertInstanceOf(PreconditionRequiredException.class, outcomes.get(2).failure().orElseThrow());
        assertTrue(outcomes.get(3).value().isPresent());
        assertEquals(List.of("casa"), store.categories.values().stream().map(Category::code).toList());
        failure(service.batch(admin(), CategoryBatchAction.DELETE,
                List.of(new Batch.Item(kept, Optional.of(0L)), new Batch.Item(kept, Optional.of(0L)))),
                InvalidInputException.class);
        failure(service.batch(seller(UUID.randomUUID()), CategoryBatchAction.DELETE,
                List.of(new Batch.Item(kept, Optional.of(0L)))), AccessDeniedException.class);
    }

    @Test
    void batchActivationChecksVersionAndSkipsCategoryAlreadyThere() {
        UUID fashion = root("moda").category().id();
        UUID home = root("casa").category().id();
        var deactivated = item(service.batch(admin(), CategoryBatchAction.DEACTIVATE,
                List.of(new Batch.Item(fashion, Optional.of(0L)), new Batch.Item(home, Optional.of(4L)))));
        Category done = deactivated.get(0).value().orElseThrow();
        assertFalse(done.active());
        assertEquals(1, done.version());
        assertInstanceOf(VersionMismatchException.class, deactivated.get(1).failure().orElseThrow());
        var again = item(service.batch(admin(), CategoryBatchAction.ACTIVATE,
                List.of(new Batch.Item(fashion, Optional.of(1L)), new Batch.Item(home, Optional.of(0L)))));
        assertEquals(2, again.get(0).value().orElseThrow().version());
        assertTrue(again.get(1).value().orElseThrow().active());
        assertEquals(0, again.get(1).value().orElseThrow().version());
    }

    static final AttributeDefinition VOLTAGE = new AttributeDefinition("voltagem", "Voltagem", AttributeType.OPTION,
            true, false, List.of("110", "220"));
    static final AttributeDefinition COLOR = new AttributeDefinition("cor", "Cor", AttributeType.TEXT, false, true,
            List.of());

    UUID child(UUID parent, String code, AttributeDefinition... attributes) {
        return item(service.create(admin(), Optional.of(parent), code, "Nome", List.of(attributes))).category().id();
    }

    static List<String> codes(CategoryService.CategoryView view) {
        return view.effectiveAttributes().stream().map(AttributeDefinition::code).toList();
    }

    @Test
    void moveCarriesSubtreeAndDescendantsInheritTheNewPath() {
        UUID fashion = root("moda", BRAND).category().id();
        UUID tShirts = child(fashion, "camisetas", SIZE);
        UUID tanks = child(tShirts, "regatas", COLOR);
        UUID electronics = root("eletronicos", VOLTAGE).category().id();
        var moved = item(service.move(admin(), tShirts, Optional.of(0L), Optional.of(electronics)));
        assertEquals(Optional.of(electronics), moved.category().parentId());
        assertEquals(1, moved.category().version());
        assertEquals(List.of("voltagem", "tamanho"), codes(moved));
        var grandchild = item(service.get(seller(UUID.randomUUID()), tanks));
        assertEquals(List.of(electronics, tShirts), grandchild.category().ancestry());
        assertEquals(List.of("voltagem", "tamanho", "cor"), codes(grandchild));
        assertEquals(0, grandchild.category().version());
        var asRoot = item(service.move(admin(), tShirts, Optional.of(1L), Optional.empty()));
        assertEquals(List.of("tamanho"), codes(asRoot));
        assertEquals(List.of(tShirts), item(service.get(admin(), tanks)).category().ancestry());
    }

    @Test
    void moveToCurrentParentWritesNothing() {
        UUID fashion = root("moda").category().id();
        UUID tShirts = child(fashion, "camisetas");
        var same = item(service.move(admin(), tShirts, Optional.of(0L), Optional.of(fashion)));
        assertEquals(0, same.category().version());
        assertEquals(0, item(service.move(admin(), fashion, Optional.of(0L), Optional.empty())).category().version());
    }

    @Test
    void moveHonorsIfMatchCapabilityAndParentExistence() {
        UUID fashion = root("moda").category().id();
        UUID electronics = root("eletronicos").category().id();
        failure(service.move(admin(), fashion, Optional.of(3L), Optional.of(electronics)),
                VersionMismatchException.class);
        failure(service.move(seller(UUID.randomUUID()), fashion, Optional.of(0L), Optional.of(electronics)),
                AccessDeniedException.class);
        failure(service.move(admin(), fashion, Optional.of(0L), Optional.of(UUID.randomUUID())),
                NotFoundException.class);
        assertEquals(List.of(), item(service.get(admin(), fashion)).category().ancestry());
    }

    @Test
    void moveRefusesCycleDepthAndAttributeConflict() {
        UUID fashion = root("moda").category().id();
        UUID tShirts = child(fashion, "camisetas", SIZE);
        UUID tanks = child(tShirts, "regatas", COLOR);
        var cycle = failure(service.move(admin(), fashion, Optional.of(0L), Optional.of(tanks)),
                DomainRuleViolation.class);
        assertEquals("category-cycle", cycle.code());
        var self = failure(service.move(admin(), fashion, Optional.of(0L), Optional.of(fashion)),
                DomainRuleViolation.class);
        assertEquals("category-cycle", self.code());

        UUID deepest = root("n0").category().id();
        for (int level = 1; level < Category.MAX_DEPTH - 1; level++)
            deepest = child(deepest, "n" + level);
        var tooDeep = failure(service.move(admin(), tShirts, Optional.of(0L), Optional.of(deepest)),
                DomainRuleViolation.class);
        assertEquals("category-too-deep", tooDeep.code());

        UUID paints = root("tintas", COLOR).category().id();
        var conflict = failure(service.move(admin(), tShirts, Optional.of(0L), Optional.of(paints)),
                DomainRuleViolation.class);
        assertEquals("attribute-conflict", conflict.code());
        assertEquals(List.of("attribute-conflict:cor"), conflict.reasons());
        assertEquals(List.of(fashion, tShirts), item(service.get(admin(), tanks)).category().ancestry());
    }

    @Test
    void inactiveNewParentIsAcceptedAndMakesSubtreeUnusable() {
        UUID archive = root("arquivo").category().id();
        item(service.revise(admin(), archive, Optional.of(0L), "Arquivo", List.of(), false));
        UUID fashion = root("moda").category().id();
        UUID tShirts = child(fashion, "camisetas");
        assertFalse(item(service.move(admin(), fashion, Optional.of(0L), Optional.of(archive))).usable());
        assertFalse(item(service.get(admin(), tShirts)).usable());
    }

    @Test
    void otherTenantCannotSeeCategory() {
        UUID fashion = root("moda").category().id();
        Actor outsider = new Actor(UUID.randomUUID(), Optional.empty(), "x", Set.of(Capability.MANAGE_TAXONOMY));
        failure(service.get(outsider, fashion), NotFoundException.class);
    }
}
