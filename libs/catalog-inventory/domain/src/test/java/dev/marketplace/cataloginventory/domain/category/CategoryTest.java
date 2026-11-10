package dev.marketplace.cataloginventory.domain.category;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class CategoryTest {
  static final UUID TENANT = UUID.randomUUID();

  static AttributeDefinition text(String code) {
    return new AttributeDefinition(code, code, AttributeType.TEXT, false, false, List.of());
  }

  static AttributeDefinition option(String code, String... options) {
    return new AttributeDefinition(code, code, AttributeType.OPTION, true, true, List.of(options));
  }

  static Category root(String code, AttributeDefinition... attributes) {
    return Category.root(UUID.randomUUID(), TENANT, code, "Nome " + code, List.of(attributes));
  }

  @Test
  void childInheritsAncestryAndAttributes() {
    Category electronics = root("eletronicos", text("marca"));
    Category celulares = Category.childOf(electronics, UUID.randomUUID(), "celulares", "Celulares",
        List.of(option("cor", "preto", "azul")), electronics.attributes());
    assertEquals(List.of(electronics.id()), celulares.ancestry());
    assertEquals(electronics.id(), celulares.parentId().orElseThrow());
    assertEquals(List.of("marca", "cor"),
        Category.effectiveAttributes(List.of(electronics, celulares)).stream().map(AttributeDefinition::code)
            .toList());
  }

  @Test
  void rejectsAttributeRedefinedAlongThePath() {
    Category electronics = root("eletronicos", text("marca"));
    var error = assertThrows(DomainRuleViolation.class, () -> Category.childOf(electronics, UUID.randomUUID(),
        "celulares", "Celulares", List.of(text("marca")), electronics.attributes()));
    assertEquals("attribute-conflict", error.code());
    assertEquals(List.of("attribute-conflict:marca"), error.reasons());
  }

  @Test
  void revisionCannotCollideWithDescendants() {
    Category electronics = root("eletronicos");
    var error = assertThrows(DomainRuleViolation.class,
        () -> electronics.revise("Eletrônicos", List.of(text("cor")), List.of(), Set.of("cor")));
    assertEquals(List.of("attribute-conflict:cor"), error.reasons());
  }

  @Test
  void limitsDepth() {
    Category current = root("n0");
    for (int level = 1; level < Category.MAX_DEPTH; level++) {
      current = Category.childOf(current, UUID.randomUUID(), "n" + level, "N" + level, List.of(), List.of());
    }
    Category deepest = current;
    var error = assertThrows(DomainRuleViolation.class,
        () -> Category.childOf(deepest, UUID.randomUUID(), "n6", "N6", List.of(), List.of()));
    assertEquals("category-too-deep", error.code());
    assertEquals(List.of(), error.reasons());
    assertEquals(List.of(String.valueOf(Category.MAX_DEPTH)), error.reason().params());
  }

  @Test
  void inactiveParentDoesNotAcceptChildren() {
    Category parent = root("moda");
    parent.deactivate();
    var error = assertThrows(DomainRuleViolation.class,
        () -> Category.childOf(parent, UUID.randomUUID(), "sapatos", "Sapatos",
            List.of(), List.of()));
    assertEquals("category-inactive", error.code());
  }

  @ParameterizedTest
  @CsvSource({"Moda", "moda_feminina", "-moda", "moda-"})
  void rejectsNonKebabCode(String code) {
    assertThrows(DomainRuleViolation.class, () -> root(code));
  }

  @ParameterizedTest
  @CsvSource({"moda", "moda-feminina", "celulares-5g", "a-b-c"})
  void acceptsKebabCode(String code) {
    assertEquals(code, root(code).code());
  }

  @Test
  void codePatternKeepsTheLanguageAndSurvivesLongInput() {
    Pattern code = Pattern.compile("^" + Category.CODE_PATTERN + "$");
    for (String valid : List.of("moda", "moda-feminina", "5g", "a-b-c"))
      assertTrue(code.matcher(valid).matches(), valid);
    for (String invalid : List.of("", "-moda", "moda-", "moda--feminina", "Moda", "moda_feminina"))
      assertFalse(code.matcher(invalid).matches(), invalid);
    // WHY: com a repetição de grupo sem quantificador possessivo, entradas assim estouravam a pilha do motor de regex.
    assertTrue(code.matcher("a-".repeat(100_000) + "a").matches());
    assertFalse(code.matcher("a-".repeat(100_000)).matches());
  }

  @Test
  void rejectsDuplicatedOwnAttribute() {
    assertThrows(DomainRuleViolation.class, () -> root("x", text("a"), text("a")));
  }

  static Category child(Category parent, String code, AttributeDefinition... attributes) {
    return Category.childOf(parent, UUID.randomUUID(), code, "Nome " + code, List.of(attributes), List.of());
  }

  @Test
  void movesUnderNewParentAndBecomesRoot() {
    Category fashion = root("moda", text("marca"));
    Category tShirts = child(fashion, "camisetas", text("tamanho"));
    Category electronics = root("eletronicos", text("voltagem"));
    Category phones = child(electronics, "celulares");
    tShirts.moveUnder(List.of(electronics, phones), 0, Set.of());
    assertEquals(List.of(electronics.id(), phones.id()), tShirts.ancestry());
    assertEquals(phones.id(), tShirts.parentId().orElseThrow());
    tShirts.moveUnder(List.of(), 0, Set.of());
    assertTrue(tShirts.ancestry().isEmpty());
    assertTrue(tShirts.parentId().isEmpty());
  }

  @Test
  void rejectsMovingUnderItselfOrDescendant() {
    Category fashion = root("moda");
    Category tShirts = child(fashion, "camisetas");
    var underDescendant = assertThrows(DomainRuleViolation.class,
        () -> fashion.moveUnder(List.of(fashion, tShirts), 1, Set.of()));
    assertEquals("category-cycle", underDescendant.code());
    var underItself = assertThrows(DomainRuleViolation.class,
        () -> fashion.moveUnder(List.of(fashion), 1, Set.of()));
    assertEquals("category-cycle", underItself.code());
    assertTrue(fashion.ancestry().isEmpty());
  }

  @Test
  void movedSubtreeMustFitTheDepthLimit() {
    List<Category> chain = new ArrayList<>(List.of(root("n0")));
    for (int level = 1; level < Category.MAX_DEPTH - 2; level++)
      chain.add(child(chain.getLast(), "n" + level));
    Category moved = root("movida");
    var error = assertThrows(DomainRuleViolation.class, () -> moved.moveUnder(chain, 2, Set.of()));
    assertEquals("category-too-deep", error.code());
    moved.moveUnder(chain, 1, Set.of());
    assertEquals(Category.MAX_DEPTH - 2, moved.ancestry().size());
  }

  @Test
  void movedSubtreeCannotRepeatAttributesOfTheNewPath() {
    Category electronics = root("eletronicos", text("marca"), text("cor"));
    Category moved = root("acessorios", text("marca"), text("material"));
    var error = assertThrows(DomainRuleViolation.class,
        () -> moved.moveUnder(List.of(electronics), 1, Set.of("cor", "tamanho")));
    assertEquals("attribute-conflict", error.code());
    assertEquals(List.of("attribute-conflict:marca", "attribute-conflict:cor"), error.reasons());
    assertTrue(moved.ancestry().isEmpty());
  }

  @Test
  void inactiveNewParentIsAccepted() {
    Category archive = root("arquivo");
    archive.deactivate();
    Category moved = root("antigos");
    moved.moveUnder(List.of(archive), 0, Set.of());
    assertEquals(List.of(archive.id()), moved.ancestry());
  }

  @Test
  void onlyCategoryWithoutChildrenIsRemovable() {
    Category fashion = root("moda");
    fashion.requireRemovable(0);
    var error = assertThrows(CategoryNotEmptyException.class, () -> fashion.requireRemovable(2));
    assertEquals(CategoryNotEmptyException.HAS_CHILDREN, error.code());
    assertEquals(List.of(CategoryNotEmptyException.HAS_CHILDREN), error.reasons());
    var products = CategoryNotEmptyException.hasProducts("moda");
    assertEquals(List.of(CategoryNotEmptyException.HAS_PRODUCTS), products.reasons());
  }
}
