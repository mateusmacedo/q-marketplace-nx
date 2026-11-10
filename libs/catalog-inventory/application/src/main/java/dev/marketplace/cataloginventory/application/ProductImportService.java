package dev.marketplace.cataloginventory.application;

import io.smallrye.mutiny.Uni;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import static dev.marketplace.cataloginventory.application.ImportSteps.sequentially;

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

  public static final String INVALID_ROW = ImportSteps.INVALID_ROW;
  public static final String DUPLICATE_SELLER_SKU = "duplicate-seller-sku";
  public static final String INCONSISTENT_PRODUCT = ImportedProductCreation.INCONSISTENT_PRODUCT;
  public static final String CATEGORY_NOT_FOUND = ImportedProductCreation.CATEGORY_NOT_FOUND;
  public static final String GROUP_REJECTED = ImportedProductCreation.GROUP_REJECTED;
  private static final String CURSOR_SEPARATOR = "|";

  private sealed interface Unit permits NewProduct, ExistingSku, Rejected {
  }

  private record NewProduct(String productRef, List<ImportRow> rows) implements Unit {
  }

  private record ExistingSku(ImportRow row, UUID skuId) implements Unit {
  }

  private record Rejected(ImportResult result) implements Unit {
  }

  private final ProductImportRepository imports;
  private final ProductRepository products;
  private final ImportedSkuUpdate skuUpdate;
  private final ImportedProductCreation productCreation;
  private final Clock clock;

  /**
   * {@code skuUpdate} aplica as linhas de SKU que já existe na loja e {@code productCreation}, os grupos de SKUs novos.
   */
  public ProductImportService(ProductImportRepository imports, ProductRepository products,
      ImportedSkuUpdate skuUpdate, ImportedProductCreation productCreation, Clock clock) {
    this.imports = imports;
    this.products = products;
    this.skuUpdate = skuUpdate;
    this.productCreation = productCreation;
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
      case ExistingSku existing -> skuUpdate.apply(seller, existing.row(), existing.skuId()).map(List::of);
      case NewProduct group -> productCreation.create(seller, group.productRef(), group.rows());
    };
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
