package dev.marketplace.cataloginventory.domain.product;

import dev.marketplace.cataloginventory.domain.DomainReasons;
import dev.marketplace.cataloginventory.domain.Reason;
import dev.marketplace.cataloginventory.domain.category.AttributeDefinition;
import dev.marketplace.cataloginventory.domain.category.AttributeType;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static dev.marketplace.cataloginventory.domain.product.ProductTest.*;
import static org.junit.jupiter.api.Assertions.*;

class CategoryChangeTest {
  static final AttributeDefinition COLLAR = new AttributeDefinition("gola", "Gola", AttributeType.OPTION, false,
      false, List.of("Redonda", "V"));
  static final AttributeDefinition WAIST = new AttributeDefinition("cintura", "Cintura (cm)", AttributeType.INTEGER,
      true, true, List.of());
  static final AttributeDefinition PANTS_SIZE = new AttributeDefinition("tamanho", "Tamanho", AttributeType.OPTION,
      true, true, List.of("38", "40"));
  static final List<AttributeDefinition> SHIRTS = List.of(BRAND, SIZE, COLLAR);
  static final List<AttributeDefinition> PANTS = List.of(BRAND, PANTS_SIZE, WAIST);

  @Test
  void listsWhatDisappearsWhatBecomesRequiredAndWhichSkusBlockTheChange() {
    Sku shirt = sku("CAM-P", "P");
    CategoryChange change = CategoryChange.of(Map.of("marca", "Acme", "gola", "Redonda"), List.of(shirt), SHIRTS,
        PANTS);

    assertEquals(List.of(new CategoryChange.RemovedAttribute("gola", "Gola", "Redonda",
        Reason.of(DomainReasons.UNKNOWN_ATTRIBUTE, "gola"))),
        change.removedAttributes());
    assertEquals(List.of(WAIST), change.newRequired(), "tamanho já era obrigatório na variação");
    assertEquals(1, change.blockingSkus().size());
    assertEquals("CAM-P", change.blockingSkus().getFirst().sellerSku());
    assertEquals(List.of("invalid-attribute:CAM-P:tamanho"),
        change.blockingSkus().getFirst().reasons().stream().map(Reason::key).toList());
  }

  @Test
  void sameAttributesMeanNoEffect() {
    CategoryChange change = CategoryChange.of(Map.of("marca", "Acme"), List.of(sku("CAM-P", "P")), SHIRTS,
        List.of(BRAND, SIZE));
    assertTrue(change.removedAttributes().isEmpty());
    assertTrue(change.newRequired().isEmpty());
    assertTrue(change.blockingSkus().isEmpty());
  }

  @Test
  void attributeThatChangesRoleIsRemovedAndRequiredAgain() {
    AttributeDefinition brandPerSku = new AttributeDefinition("marca", "Marca", AttributeType.TEXT, true, true,
        List.of());
    Sku plain = Sku.create(UUID.randomUUID(), "CAM-1", Map.of(), false, null);
    CategoryChange change = CategoryChange.of(Map.of("marca", "Acme"), List.of(plain), List.of(BRAND),
        List.of(brandPerSku));
    assertEquals(List.of("marca"), change.removedAttributes().stream().map(CategoryChange.RemovedAttribute::code)
        .toList());
    assertEquals(List.of(brandPerSku), change.newRequired());
    assertTrue(change.blockingSkus().isEmpty(), "falta de valor vira pendência, não bloqueia a troca");
  }

  @Test
  void productDelegatesWithTheSavedSkusAndTheDraftAttributes() {
    Product product = complete();
    CategoryChange change = product.categoryChange(Map.of("marca", "Acme", "gola", "V"), SHIRTS, PANTS);
    assertEquals("V", change.removedAttributes().getFirst().value());
    assertEquals(List.of("CAM-P", "CAM-M"),
        change.blockingSkus().stream().map(CategoryChange.BlockingSku::sellerSku).toList());
  }
}
