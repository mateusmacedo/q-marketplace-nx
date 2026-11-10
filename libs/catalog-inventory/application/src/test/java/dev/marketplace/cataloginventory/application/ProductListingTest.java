package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import dev.marketplace.cataloginventory.domain.product.StatusChange;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static dev.marketplace.cataloginventory.application.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductListingTest {
  final Catalog catalog = new Catalog();
  final ProductService products = catalog.products;
  final Actor seller = catalog.seller;

  Product titled(String title, ProductService.NewSku... skus) {
    catalog.clock.advance(Duration.ofMinutes(1));
    return item(products.create(seller, catalog.category, "BRL", title, "", Map.of("marca", "Acme"),
        List.of(skus)));
  }

  List<String> titles(Optional<String> text, ProductSort sort) {
    List<String> seen = new ArrayList<>();
    Optional<String> cursor = Optional.empty();
    do {
      Page<Product> page = item(products.list(seller, Optional.empty(), Optional.empty(), text, sort, cursor, 1));
      page.items().forEach(p -> seen.add(p.title()));
      cursor = page.nextCursor();
    } while (cursor.isPresent());
    return seen;
  }

  @Test
  void everySortWalksAllPagesInOrderWithTheCompositeCursor() {
    titled("Banana");
    titled("árvore");
    titled("Abacaxi");
    assertEquals(List.of("Abacaxi", "árvore", "Banana"), titles(Optional.empty(), ProductSort.UPDATED_AT_DESC));
    assertEquals(List.of("Banana", "árvore", "Abacaxi"), titles(Optional.empty(), ProductSort.CREATED_AT));
    assertEquals(List.of("Abacaxi", "árvore", "Banana"), titles(Optional.empty(), ProductSort.TITLE));
    assertEquals(List.of("Banana", "árvore", "Abacaxi"), titles(Optional.empty(), ProductSort.TITLE_DESC));
  }

  @Test
  void equalSortValuesAreTieBrokenById() {
    for (int i = 0; i < 4; i++)
      item(products.create(seller, catalog.category, "BRL", "Camiseta", "", Map.of(), List.of()));
    assertEquals(4, titles(Optional.empty(), ProductSort.UPDATED_AT).size());
    assertEquals(4, titles(Optional.empty(), ProductSort.TITLE_DESC).size());
  }

  @Test
  void textMatchesTheTitleWithoutCaseOrAccentsAndTheSellerSku() {
    titled("Camiseta Básica", sku("CAM-P", "P"));
    titled("Caneca 300ml", sku("CAN-01", "P"));
    assertEquals(List.of("Camiseta Básica"), titles(Optional.of("  CAMISETA   basica "), ProductSort.TITLE));
    assertEquals(List.of("Caneca 300ml"), titles(Optional.of("can-0"), ProductSort.TITLE));
    assertEquals(List.of("Caneca 300ml", "Camiseta Básica"), titles(Optional.of("   "), ProductSort.DEFAULT));
  }

  @Test
  void cursorOnlyContinuesTheSameSortAndSearch() {
    titled("A1");
    titled("A2");
    Optional<String> cursor = item(products.list(seller, Optional.empty(), Optional.empty(), Optional.of("a"),
        ProductSort.TITLE, Optional.empty(), 1)).nextCursor();
    assertTrue(cursor.isPresent());
    failure(products.list(seller, Optional.empty(), Optional.empty(), Optional.of("a"), ProductSort.TITLE_DESC,
        cursor, 1), InvalidInputException.class);
    failure(products.list(seller, Optional.empty(), Optional.empty(), Optional.of("a1"), ProductSort.TITLE,
        cursor, 1), InvalidInputException.class);
    failure(products.list(seller, Optional.of(ProductStatus.DRAFT), Optional.empty(), Optional.of("a"),
        ProductSort.TITLE, cursor, 1), InvalidInputException.class);
    failure(products.list(seller, Optional.empty(), Optional.empty(), Optional.empty(), ProductSort.UPDATED_AT,
        Optional.of("updatedAt|x|y|z"), 1), InvalidInputException.class);
    assertEquals(1, item(products.list(seller, Optional.empty(), Optional.empty(), Optional.of("A"),
        ProductSort.TITLE, cursor, 1)).items().size());
  }

  @Test
  void textAndSortAreValidated() {
    failure(products.list(seller, Optional.empty(), Optional.empty(), Optional.of("x".repeat(101)),
        ProductSort.DEFAULT, Optional.empty(), 10), InvalidInputException.class);
    failure(products.list(seller, Optional.empty(), Optional.empty(), Optional.of("ca\0neca"),
        ProductSort.DEFAULT, Optional.empty(), 10), InvalidInputException.class);
    assertThrows(InvalidInputException.class, () -> ProductSort.parse(Optional.of("price")));
    assertEquals(ProductSort.DEFAULT, ProductSort.parse(Optional.of(" ")));
    assertEquals(ProductSort.TITLE_DESC, ProductSort.parse(Optional.of("-title")));
  }

  @Test
  void outOfStockFilterUsesTheEffectiveStatus() {
    Product product = catalog.active(sku("CAM-P", "P"));
    assertEquals(List.of(product.id()), ids(ProductStatus.OUT_OF_STOCK));
    assertEquals(List.of(), ids(ProductStatus.ACTIVE));
    item(catalog.inventory.adjust(seller, product.skus().get(0).id(), Optional.empty(), 5));
    assertEquals(List.of(), ids(ProductStatus.OUT_OF_STOCK));
    assertEquals(List.of(product.id()), ids(ProductStatus.ACTIVE));
  }

  List<UUID> ids(ProductStatus status) {
    return item(products.list(admin(), Optional.of(status), Optional.empty(), Optional.empty(),
        ProductSort.DEFAULT, Optional.empty(), 10)).items().stream().map(Product::id).toList();
  }

  @Test
  void lastRejectionOnlyForDraftsReturnedByModeration() {
    Product rejected = catalog.offered(catalog.draft(sku("CAM-P", "P")));
    Product fresh = catalog.draft(sku("CAM-M", "M"));
    Product resubmitted = catalog.offered(catalog.draft(sku("CAM-G", "G")));
    for (Product p : List.of(rejected, resubmitted)) {
      item(products.submit(seller, p.id(), Optional.empty()));
      item(catalog.moderation.reject(moderator(), p.id(), Optional.empty(), List.of(),
          Optional.of("Foto ilegível")));
    }
    item(products.submit(seller, resubmitted.id(), Optional.empty()));
    List<Product> listed = item(products.list(seller, Optional.empty(), Optional.empty(), Optional.empty(),
        ProductSort.DEFAULT, Optional.empty(), 10)).items();
    Map<UUID, StatusChange> rejections = item(products.lastRejections(listed));
    assertEquals(Set.of(rejected.id()), rejections.keySet());
    assertEquals("Foto ilegível", rejections.get(rejected.id()).reason());
    assertEquals("mod", rejections.get(rejected.id()).actor());
    assertFalse(rejections.containsKey(fresh.id()));
  }

  @Test
  void offersOfReadsEveryOfferedSkuAtOnce() {
    Product offered = catalog.offered(catalog.draft(sku("CAM-P", "P"), sku("CAM-M", "M")));
    Product bare = catalog.draft(sku("BON-P", "P"));
    var found = item(catalog.offers.offersOf(List.of(offered, bare)));
    assertEquals(Set.copyOf(offered.skus().stream().map(s -> s.id()).toList()), found.keySet());
    assertEquals(Map.of(), item(catalog.offers.offersOf(List.of())));
  }

  @Test
  void titleKeyIgnoresCaseAccentsAndCompatibilityForms() {
    assertEquals("camiseta basica fi", ProductText.titleKey("  CAMISETA\tBÁSICA ﬁ "));
    assertEquals("istanbul", ProductText.titleKey("İstanbul"));
    assertEquals(ProductText.TITLE_KEY_MAX_CODE_POINTS, ProductText.titleKey("ﷺ".repeat(200)).length());
  }
}
