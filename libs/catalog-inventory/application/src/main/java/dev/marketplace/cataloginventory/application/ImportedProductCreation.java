package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.application.ImportResult.ImportOutcome;
import dev.marketplace.cataloginventory.application.ProductService.NewSku;
import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.inventory.StockLevel;
import dev.marketplace.cataloginventory.domain.offer.Price;
import dev.marketplace.cataloginventory.domain.product.Dimensions;
import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.Sku;
import io.smallrye.mutiny.Uni;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import static dev.marketplace.cataloginventory.application.ImportSteps.failed;
import static dev.marketplace.cataloginventory.application.ImportSteps.invalidRow;
import static dev.marketplace.cataloginventory.application.ImportSteps.sequentially;

/**
 * Grupo de linhas da planilha com SKUs novos e a mesma referência de produto: cria o produto em rascunho com todos os
 * SKUs e depois define oferta e estoque de cada um, pelos casos de uso de produto, oferta e estoque, como o seller que
 * enviou o lote.
 */
public final class ImportedProductCreation {
  static final String INCONSISTENT_PRODUCT = "inconsistent-product";
  static final String CATEGORY_NOT_FOUND = "category-not-found";
  static final String GROUP_REJECTED = "group-rejected";

  /** Linha de SKU novo já validada, pronta para criar produto, oferta e estoque. */
  private record Prepared(ImportRow row, NewSku sku, Price price, int onHand) {
  }

  private final CategoryRepository categories;
  private final ProductService productService;
  private final OfferService offerService;
  private final InventoryService inventoryService;

  public ImportedProductCreation(CategoryRepository categories, ProductService productService,
      OfferService offerService, InventoryService inventoryService) {
    this.categories = categories;
    this.productService = productService;
    this.offerService = offerService;
    this.inventoryService = inventoryService;
  }

  /**
   * Cria o produto {@code productRef} das linhas {@code rows}. Linha inválida, divergência entre as linhas ou
   * categoria desconhecida recusam o grupo inteiro antes de qualquer gravação; recusa de negócio vira linha
   * {@code FAILED}.
   */
  Uni<List<ImportResult>> create(Actor seller, String productRef, List<ImportRow> rows) {
    List<Prepared> prepared = new ArrayList<>();
    Map<Integer, ImportResult> refused = new LinkedHashMap<>();
    for (ImportRow row : rows) {
      try {
        prepared.add(prepare(row));
      } catch (DomainRuleViolation e) {
        refused.put(row.lineNumber(), failed(row, null, e));
      }
    }
    if (!refused.isEmpty())
      return Uni.createFrom().item(rejectGroup(productRef, rows, refused));
    List<String> divergent = divergentFields(prepared);
    if (!divergent.isEmpty())
      return Uni.createFrom().item(refuseAll(rows, INCONSISTENT_PRODUCT, "Linhas do produto "
          + productRef + " divergem da primeira em: " + String.join(", ", divergent)));
    ImportRow first = prepared.getFirst().row();
    return categories.idByCode(seller.tenantId(), first.categoryCode()).flatMap(categoryId -> {
      if (categoryId.isEmpty())
        return Uni.createFrom().item(refuseAll(rows, CATEGORY_NOT_FOUND,
            "Categoria " + first.categoryCode() + " não existe no tenant"));
      return productService.create(seller, categoryId.get(), first.currency(), first.title(),
          first.description(), first.attributes(), prepared.stream().map(Prepared::sku).toList())
          .flatMap(product -> sequentially(prepared, p -> offerAndStock(seller, product, p)))
          .onFailure(ImportSteps::isRefusal)
          .recoverWithItem(e -> rows.stream().map(row -> failed(row, null, e)).toList());
    });
  }

  /**
   * Define oferta e estoque do SKU recém-criado; falha aqui deixa o produto
   * criado e registra a linha como {@code FAILED} com o id dele.
   */
  private Uni<ImportResult> offerAndStock(Actor seller, Product product, Prepared p) {
    UUID skuId = product.skus().stream().filter(s -> s.sellerSku().equals(p.sku().sellerSku())).findFirst()
        .orElseThrow().id();
    return offerService.define(seller, skuId, Optional.empty(), p.price())
        .flatMap(ignored -> p.onHand() == 0
            ? Uni.createFrom().voidItem()
            : inventoryService.adjust(seller, skuId, Optional.empty(), p.onHand()))
        .map(ignored -> ImportResult.succeeded(p.row(), ImportOutcome.CREATED, product.id()))
        .onFailure(ImportSteps::isRefusal).recoverWithItem(e -> failed(p.row(), product.id(), e));
  }

  /**
   * Valida sem gravar o que criação, oferta e estoque do SKU novo exigem;
   * recusa com o código do domínio ou {@code invalid-row}.
   */
  private static Prepared prepare(ImportRow row) {
    if (row.error() != null || row.productError() != null)
      throw invalidRow(Objects.requireNonNullElse(row.error(), row.productError()));
    List<String> missing = new ArrayList<>();
    if (row.categoryCode() == null)
      missing.add("categoria");
    if (row.title() == null)
      missing.add("título");
    if (row.physical() == null)
      missing.add("indicador de SKU físico");
    if (!missing.isEmpty())
      throw invalidRow("SKU novo exige " + String.join(", ", missing));
    NewSku sku = new NewSku(row.sellerSku(), row.variantAttributes(), row.physical(), dimensions(row));
    Sku.create(UUID.randomUUID(), sku.sellerSku(), sku.variantAttributes(), sku.physical(), sku.dimensions());
    return new Prepared(row, sku, ImportSteps.price(row), StockLevel.requireOnHand(row.onHand()));
  }

  /** Sem nenhuma medida, o SKU não tem dimensões; medida parcial o domínio recusa. */
  private static Dimensions dimensions(ImportRow row) {
    if (row.heightCm() == null && row.widthCm() == null && row.lengthCm() == null && row.weightGrams() == null)
      return null;
    return new Dimensions(row.heightCm(), row.widthCm(), row.lengthCm(),
        row.weightGrams() == null ? 0 : row.weightGrams());
  }

  /** Campos do produto em que alguma linha do grupo difere da primeira. */
  private static List<String> divergentFields(List<Prepared> prepared) {
    ImportRow first = prepared.getFirst().row();
    Set<String> fields = new LinkedHashSet<>();
    for (Prepared p : prepared) {
      ImportRow row = p.row();
      if (!first.categoryCode().equals(row.categoryCode()))
        fields.add("categoria");
      if (!first.title().equals(row.title()))
        fields.add("título");
      if (!Objects.requireNonNullElse(first.description(), "")
          .equals(Objects.requireNonNullElse(row.description(), "")))
        fields.add("descrição");
      if (!first.attributes().equals(row.attributes()))
        fields.add("atributos");
      if (!first.currency().equals(row.currency()))
        fields.add("moeda");
    }
    return List.copyOf(fields);
  }

  private static List<ImportResult> rejectGroup(String productRef, List<ImportRow> rows,
      Map<Integer, ImportResult> refused) {
    String lines = refused.keySet().stream().map(String::valueOf).collect(Collectors.joining(", "));
    String message = "Produto " + productRef + " não criado por erro na(s) linha(s) " + lines;
    return rows.stream().map(row -> refused.containsKey(row.lineNumber())
        ? refused.get(row.lineNumber())
        : ImportResult.failed(row, null, GROUP_REJECTED, message)).toList();
  }

  private static List<ImportResult> refuseAll(List<ImportRow> rows, String code, String message) {
    return rows.stream().map(row -> ImportResult.failed(row, null, code, message)).toList();
  }
}
