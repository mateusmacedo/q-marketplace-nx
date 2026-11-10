package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.application.ImportResult.ImportOutcome;
import dev.marketplace.cataloginventory.application.ProductService.NewSku;
import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.Reason;
import dev.marketplace.cataloginventory.domain.Money;
import dev.marketplace.cataloginventory.domain.inventory.StockLevel;
import dev.marketplace.cataloginventory.domain.offer.Offer;
import dev.marketplace.cataloginventory.domain.offer.Price;
import dev.marketplace.cataloginventory.domain.product.Dimensions;
import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.Sku;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Importação de produtos por planilha, processada em segundo plano pelos casos
 * de uso de produto, oferta e estoque, como o seller que enviou: SKU existente
 * na loja recebe só preço e estoque; SKUs novos com a mesma referência formam
 * um produto em rascunho.
 */
public final class ProductImportService {
  /** Lote com os resultados já gravados, em ordem de linha. */
  public record Report(ProductImport job, List<ImportResult> lines) {
  }

  public static final String INVALID_ROW = ApplicationReasons.INVALID_ROW.code();
  public static final String DUPLICATE_SELLER_SKU = "duplicate-seller-sku";
  public static final String INCONSISTENT_PRODUCT = "inconsistent-product";
  public static final String CATEGORY_NOT_FOUND = "category-not-found";
  public static final String GROUP_REJECTED = "group-rejected";
  private static final String IMPORT = "IMPORT";
  private static final String CURSOR_SEPARATOR = "|";

  private sealed interface Unit permits NewProduct, ExistingSku, Rejected {
  }

  private record NewProduct(String productRef, List<ImportRow> rows) implements Unit {
  }

  private record ExistingSku(ImportRow row, UUID skuId) implements Unit {
  }

  private record Rejected(ImportResult result) implements Unit {
  }

  /** Linha de SKU novo já validada, pronta para criar produto, oferta e estoque. */
  private record Prepared(ImportRow row, NewSku sku, Price price, int onHand) {
  }

  private final ProductImportRepository imports;
  private final ProductRepository products;
  private final CategoryRepository categories;
  private final OfferRepository offers;
  private final ProductAccess access;
  private final ProductService productService;
  private final OfferService offerService;
  private final InventoryService inventoryService;
  private final Clock clock;

  public ProductImportService(ProductImportRepository imports, ProductRepository products,
      CategoryRepository categories, OfferRepository offers, ProductService productService,
      OfferService offerService, InventoryService inventoryService, Clock clock) {
    this.imports = imports;
    this.products = products;
    this.categories = categories;
    this.offers = offers;
    this.access = new ProductAccess(products);
    this.productService = productService;
    this.offerService = offerService;
    this.inventoryService = inventoryService;
    this.clock = clock;
  }

  /**
   * Registra o arquivo, já validado pelo adaptador, como lote {@code PENDING}
   * da loja do seller. Exige {@code MANAGE_OWN_PRODUCTS} e loja no token.
   */
  public Uni<ProductImport> submit(Actor actor, String content, int totalLines) {
    return Uni.createFrom().deferred(() -> {
      UUID store = ProductAccess.requireSeller(actor);
      ProductImport job = ProductImport.pending(UUID.randomUUID(), actor.tenantId(), store, actor.subject(),
          totalLines, clock.instant());
      return imports.insert(job, content).replaceWith(job);
    });
  }

  /**
   * Lote com os resultados já gravados. Só o seller da loja que enviou vê; de
   * outra loja ou tenant, {@link NotFoundException}.
   */
  public Uni<Report> get(Actor actor, UUID id) {
    return Uni.createFrom().deferred(() -> {
      UUID store = ProductAccess.requireSeller(actor);
      return imports.findImport(actor.tenantId(), store, id)
          .map(found -> found.orElseThrow(() -> new NotFoundException("Lote de importação")))
          .flatMap(job -> imports.results(job.id()).map(lines -> new Report(job, lines)));
    });
  }

  /**
   * Lote com as contagens por desfecho, sem os resultados de cada linha, para acompanhar o processamento; o
   * acesso é o de {@link #get}.
   */
  public Uni<ProductImportRepository.Summary> summary(Actor actor, UUID id) {
    return Uni.createFrom().deferred(() -> {
      UUID store = ProductAccess.requireSeller(actor);
      return imports.findSummary(actor.tenantId(), store, id)
          .map(found -> found.orElseThrow(() -> new NotFoundException("Lote de importação")));
    });
  }

  /**
   * Lotes da loja do seller, do mais recente ao mais antigo, com as contagens por desfecho, por cursor (criação e
   * id do último visto). Exige {@code MANAGE_OWN_PRODUCTS} e loja no token; cursor malformado ou {@code limit} fora
   * de 1 a 100: {@link InvalidInputException}.
   */
  public Uni<Page<ProductImportRepository.Summary>> list(Actor actor, Optional<String> cursorKey, int limit) {
    return Uni.createFrom().deferred(() -> {
      UUID store = ProductAccess.requireSeller(actor);
      Optional<ProductImportRepository.Position> after = cursorKey.map(ProductImportService::position);
      return Page.fetch(limit, fetchLimit -> imports.list(actor.tenantId(), store, after, fetchLimit),
          summary -> summary.job().createdAt() + CURSOR_SEPARATOR + summary.job().id());
    });
  }

  /** Reivindica o lote mais antigo pendente ou sem progresso há mais de {@code lease}. */
  public Uni<Optional<ProductImportRepository.Claimed>> claimNext(Duration lease) {
    return Uni.createFrom().deferred(() -> {
      Instant now = clock.instant();
      return imports.claimNext(now, now.minus(lease));
    });
  }

  /**
   * Processa as linhas sem resultado, gravando cada unidade ao terminar, conclui
   * o lote e devolve só os resultados novos. Recusa de negócio vira linha
   * {@code FAILED}; falha técnica interrompe, e o lote volta após o lease.
   */
  public Uni<List<ImportResult>> process(ProductImport job, List<ImportRow> rows) {
    Actor seller = job.seller();
    return imports.results(job.id()).flatMap(recorded -> {
      Set<Integer> done = recorded.stream().map(ImportResult::lineNumber).collect(Collectors.toSet());
      Set<String> pendingSkus = rows.stream().filter(row -> !done.contains(row.lineNumber()))
          .map(ImportRow::sellerSku).filter(Objects::nonNull).collect(Collectors.toSet());
      return products.skuIdsBySellerSku(job.tenantId(), job.storeId(), pendingSkus)
          .flatMap(existing -> sequentially(plan(rows, done, existing), unit -> run(seller, unit)
              .flatMap(results -> imports.recordResults(job.id(), results, clock.instant())
                  .replaceWith(results))));
    }).map(batches -> batches.stream().flatMap(List::stream).toList())
        .flatMap(results -> imports.complete(job.id(), clock.instant()).replaceWith(results));
  }

  /**
   * Divide as linhas pendentes em unidades, na ordem do arquivo: SKU existente
   * sozinho e SKUs novos agrupados pela referência do produto. SKU repetido no
   * arquivo, conferido sobre todas as linhas, falha a partir da segunda vez.
   */
  private static List<Unit> plan(List<ImportRow> rows, Set<Integer> done, Map<String, UUID> existing) {
    List<Unit> units = new ArrayList<>();
    Map<String, List<ImportRow>> groups = new HashMap<>();
    Set<String> seen = new HashSet<>();
    for (ImportRow row : rows) {
      boolean repeated = row.sellerSku() != null && !seen.add(row.sellerSku());
      if (done.contains(row.lineNumber()))
        continue;
      UUID skuId = row.sellerSku() == null ? null : existing.get(row.sellerSku());
      if (repeated)
        units.add(new Rejected(ImportResult.failed(row, null, DUPLICATE_SELLER_SKU,
            "SKU do seller repetido no arquivo; só a primeira linha dele é importada")));
      else if (skuId != null)
        units.add(new ExistingSku(row, skuId));
      else if (row.productRef() == null)
        units.add(new Rejected(ImportResult.failed(row, null, INVALID_ROW, row.error() != null
            ? row.error()
            : "SKU novo exige a referência do produto")));
      else
        groups.computeIfAbsent(row.productRef(), ref -> {
          List<ImportRow> group = new ArrayList<>();
          units.add(new NewProduct(ref, group));
          return group;
        }).add(row);
    }
    return units;
  }

  private Uni<List<ImportResult>> run(Actor seller, Unit unit) {
    return switch (unit) {
      case Rejected rejected -> Uni.createFrom().item(List.of(rejected.result()));
      case ExistingSku existing -> update(seller, existing.row(), existing.skuId()).map(List::of);
      case NewProduct group -> create(seller, group);
    };
  }

  /**
   * Aplica ao SKU existente só preço e estoque, cada um apenas se mudou. Produto
   * arquivado: {@code invalid-transition}. {@code UPDATED} se algo mudou,
   * senão {@code UNCHANGED}.
   */
  private Uni<ImportResult> update(Actor seller, ImportRow row, UUID skuId) {
    if (row.error() != null)
      return Uni.createFrom().item(ImportResult.failed(row, null, INVALID_ROW, row.error()));
    return access.ownedBySku(seller, skuId).flatMap(product -> Uni.createFrom().deferred(() -> {
      product.requireNotArchived(IMPORT);
      Price price = price(row);
      int onHand = StockLevel.requireOnHand(row.onHand());
      return changePrice(seller, product, skuId, price)
          .flatMap(priceChanged -> changeStock(seller, skuId, onHand)
              .map(stockChanged -> priceChanged || stockChanged));
    }).map(
        changed -> ImportResult.succeeded(row, changed ? ImportOutcome.UPDATED : ImportOutcome.UNCHANGED, product.id()))
        .onFailure(ProductImportService::isRefusal).recoverWithItem(e -> failed(row, product.id(), e)))
        .onFailure(ProductImportService::isRefusal).recoverWithItem(e -> failed(row, null, e));
  }

  /**
   * Troca o preço só se difere do atual, com a versão lida da oferta como
   * If-Match implícito; sem oferta, cria. Devolve se houve mudança.
   */
  private Uni<Boolean> changePrice(Actor seller, Product product, UUID skuId, Price price) {
    return offers.ofSku(product.tenantId(), skuId).flatMap(current -> {
      if (current.isPresent() && current.get().price().equals(price))
        return Uni.createFrom().item(false);
      return offerService.define(seller, skuId, current.map(Offer::version), price).replaceWith(true);
    });
  }

  /**
   * Define o estoque absoluto só se difere do atual, sem If-Match: reservas
   * concorrentes também incrementam a versão do saldo.
   */
  private Uni<Boolean> changeStock(Actor seller, UUID skuId, int onHand) {
    return inventoryService.get(seller, skuId).flatMap(level -> level.onHand() == onHand
        ? Uni.createFrom().item(false)
        : inventoryService.adjust(seller, skuId, Optional.empty(), onHand).replaceWith(true));
  }

  /**
   * Cria o produto do grupo em rascunho com todos os SKUs e depois define
   * oferta e estoque de cada um. Linha inválida, divergência entre as linhas ou
   * categoria desconhecida recusam o grupo inteiro antes de qualquer gravação.
   */
  private Uni<List<ImportResult>> create(Actor seller, NewProduct group) {
    List<Prepared> prepared = new ArrayList<>();
    Map<Integer, ImportResult> refused = new LinkedHashMap<>();
    for (ImportRow row : group.rows()) {
      try {
        prepared.add(prepare(row));
      } catch (DomainRuleViolation e) {
        refused.put(row.lineNumber(), failed(row, null, e));
      }
    }
    if (!refused.isEmpty())
      return Uni.createFrom().item(rejectGroup(group, refused));
    List<String> divergent = divergentFields(prepared);
    if (!divergent.isEmpty())
      return Uni.createFrom().item(refuseAll(group, INCONSISTENT_PRODUCT, "Linhas do produto "
          + group.productRef() + " divergem da primeira em: " + String.join(", ", divergent)));
    ImportRow first = prepared.getFirst().row();
    return categories.idByCode(seller.tenantId(), first.categoryCode()).flatMap(categoryId -> {
      if (categoryId.isEmpty())
        return Uni.createFrom().item(refuseAll(group, CATEGORY_NOT_FOUND,
            "Categoria " + first.categoryCode() + " não existe no tenant"));
      return productService.create(seller, categoryId.get(), first.currency(), first.title(),
          first.description(), first.attributes(), prepared.stream().map(Prepared::sku).toList())
          .flatMap(product -> sequentially(prepared, p -> offerAndStock(seller, product, p)))
          .onFailure(ProductImportService::isRefusal)
          .recoverWithItem(e -> group.rows().stream().map(row -> failed(row, null, e)).toList());
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
        .onFailure(ProductImportService::isRefusal).recoverWithItem(e -> failed(p.row(), product.id(), e));
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
    return new Prepared(row, sku, price(row), StockLevel.requireOnHand(row.onHand()));
  }

  /** Sem nenhuma medida, o SKU não tem dimensões; medida parcial o domínio recusa. */
  private static Dimensions dimensions(ImportRow row) {
    if (row.heightCm() == null && row.widthCm() == null && row.lengthCm() == null && row.weightGrams() == null)
      return null;
    return new Dimensions(row.heightCm(), row.widthCm(), row.lengthCm(),
        row.weightGrams() == null ? 0 : row.weightGrams());
  }

  private static Price price(ImportRow row) {
    Money list = row.listPrice() == null ? null : new Money(row.listPrice(), row.currency());
    return new Price(list, new Money(row.salePrice(), row.currency()));
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

  private static List<ImportResult> rejectGroup(NewProduct group, Map<Integer, ImportResult> refused) {
    String lines = refused.keySet().stream().map(String::valueOf).collect(Collectors.joining(", "));
    String message = "Produto " + group.productRef() + " não criado por erro na(s) linha(s) " + lines;
    return group.rows().stream().map(row -> refused.containsKey(row.lineNumber())
        ? refused.get(row.lineNumber())
        : ImportResult.failed(row, null, GROUP_REJECTED, message)).toList();
  }

  private static List<ImportResult> refuseAll(NewProduct group, String code, String message) {
    return group.rows().stream().map(row -> ImportResult.failed(row, null, code, message)).toList();
  }

  /** Recusas de negócio viram linha {@code FAILED}; o restante é falha técnica. */
  private static boolean isRefusal(Throwable e) {
    return e instanceof DomainRuleViolation || e instanceof DuplicateException || e instanceof NotFoundException
        || e instanceof ConcurrentUpdateException || e instanceof VersionMismatchException
        || e instanceof PreconditionRequiredException || e instanceof InvalidInputException
        || e instanceof AccessDeniedException;
  }

  /** Código estável da recusa, igual ao {@code type} que a API responderia. */
  private static String codeOf(Throwable e) {
    return switch (e) {
      case DomainRuleViolation violation -> violation.code();
      case DuplicateException duplicate -> duplicate.code();
      case NotFoundException notFound -> "not-found";
      case ConcurrentUpdateException concurrent -> "concurrent-update";
      case VersionMismatchException mismatch -> "version-mismatch";
      case PreconditionRequiredException precondition -> "precondition-required";
      case AccessDeniedException denied -> "access-denied";
      default -> "invalid-request";
    };
  }

  /** Linha inválida; a descrição do problema na linha é o próprio texto ao seller. */
  private static DomainRuleViolation invalidRow(String message) {
    return new DomainRuleViolation(Reason.of(ApplicationReasons.INVALID_ROW).withMessage(message), message);
  }

  /** A recusa de negócio leva à linha os motivos dela, como no corpo de erro da API. */
  private static ImportResult failed(ImportRow row, UUID productId, Throwable e) {
    String message = Objects.requireNonNullElse(e.getMessage(), codeOf(e));
    List<Reason> reasons = e instanceof DomainRuleViolation violation ? violation.details() : List.of();
    return ImportResult.failed(row, productId, codeOf(e), message, reasons);
  }

  private static <T, R> Uni<List<R>> sequentially(List<T> items, Function<T, Uni<R>> step) {
    return Multi.createFrom().iterable(items).onItem().<R>transformToUniAndConcatenate(step::apply).collect()
        .asList();
  }

  private static ProductImportRepository.Position position(String key) {
    String[] parts = key.split("\\" + CURSOR_SEPARATOR, 2);
    try {
      if (parts.length != 2)
        throw new IllegalArgumentException("sem separador");
      return new ProductImportRepository.Position(Instant.parse(parts[0]), UUID.fromString(parts[1]));
    } catch (IllegalArgumentException | DateTimeParseException e) {
      throw new InvalidInputException("cursor inválido");
    }
  }
}
