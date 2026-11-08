package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.category.AttributeDefinition;
import dev.marketplace.cataloginventory.domain.category.AttributeType;
import dev.marketplace.cataloginventory.domain.category.Category;
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
        Page<Category> first = item(service.children(admin(), Optional.empty(), Optional.empty(), 2));
        assertEquals(List.of("a", "b"), first.items().stream().map(Category::code).toList());
        Page<Category> second = item(service.children(admin(), Optional.empty(), first.nextCursor(), 2));
        assertEquals(List.of("c"), second.items().stream().map(Category::code).toList());
        assertTrue(second.nextCursor().isEmpty());
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
