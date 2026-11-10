package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.DomainReasons;
import dev.marketplace.cataloginventory.domain.category.Category;
import dev.marketplace.cataloginventory.domain.category.CategoryNotEmptyException;
import dev.marketplace.cataloginventory.domain.inventory.*;
import dev.marketplace.cataloginventory.domain.offer.Offer;
import dev.marketplace.cataloginventory.domain.product.*;
import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;

/**
 * Repositórios em memória que guardam cópias, como um banco, e aplicam o
 * controle otimista de versão.
 */
final class InMemoryStore
    implements
      CategoryRepository,
      ProductRepository,
      OfferRepository,
      SellerEligibilityRepository,
      InventoryRepository,
      ReservationRepository,
      ProductImportRepository,
      CatalogStatsRepository,
      ModerationReasonRepository {
  final Map<UUID, Category> categories = new LinkedHashMap<>();
  final Map<UUID, Product> products = new LinkedHashMap<>();
  final Map<UUID, Offer> offers = new LinkedHashMap<>();
  final Map<UUID, List<StatusChange>> history = new HashMap<>();
  final Map<UUID, SellerStatus> sellers = new HashMap<>();
  final Map<UUID, Instant> decidedAt = new HashMap<>();
  final Set<String> delivered = new HashSet<>();
  final Map<UUID, StockLevel> stock = new HashMap<>();
  final Map<UUID, Reservation> reservations = new LinkedHashMap<>();
  final List<String> adjustments = new ArrayList<>();
  final Map<UUID, ProductImport> imports = new LinkedHashMap<>();
  final Map<UUID, String> importContents = new HashMap<>();
  final Map<UUID, TreeMap<Integer, ImportResult>> importResults = new HashMap<>();
  final Map<UUID, Map<String, ModerationReasonEntry>> moderationReasons = new HashMap<>();
  /** Leituras do catálogo de motivos, para conferir quando a decisão dispensa a leitura. */
  int catalogReads;
  /** Consultas de situação de loja em lote, para conferir uma por página. */
  int statusQueries;
  /** Roda no início de cada gravação de produto; o teste o usa para simular outro escritor. */
  Consumer<Product> beforeProductUpdate = product -> {
  };

  public synchronized Uni<List<Category>> chain(UUID tenantId, UUID categoryId) {
    Category self = categories.get(categoryId);
    if (self == null || !self.tenantId().equals(tenantId))
      return Uni.createFrom().item(List.of());
    List<Category> chain = new ArrayList<>();
    self.ancestry().forEach(id -> chain.add(copy(categories.get(id))));
    chain.add(copy(self));
    return Uni.createFrom().item(chain);
  }

  public synchronized Uni<Optional<UUID>> idByCode(UUID tenantId, String code) {
    return Uni.createFrom().item(categories.values().stream()
        .filter(c -> c.tenantId().equals(tenantId) && c.code().equals(code)).map(Category::id).findFirst());
  }

  public synchronized Uni<Set<String>> descendantAttributeCodes(UUID tenantId, UUID categoryId) {
    Set<String> codes = new HashSet<>();
    categories.values().stream().filter(c -> c.ancestry().contains(categoryId))
        .forEach(c -> c.attributes().forEach(a -> codes.add(a.code())));
    return Uni.createFrom().item(codes);
  }

  public synchronized Uni<List<Category>> children(UUID tenantId, Optional<UUID> parentId, Optional<String> afterCode,
      int limit) {
    return Uni.createFrom().item(categories.values().stream()
        .filter(c -> c.tenantId().equals(tenantId) && c.parentId().equals(parentId))
        .filter(c -> afterCode.map(a -> c.code().compareTo(a) > 0).orElse(true))
        .sorted(Comparator.comparing(Category::code)).limit(limit).map(InMemoryStore::copy).toList());
  }

  public synchronized Uni<Void> insert(Category category) {
    boolean taken = categories.values().stream()
        .anyMatch(c -> c.tenantId().equals(category.tenantId()) && c.code().equals(category.code()));
    if (taken)
      return Uni.createFrom().failure(new DuplicateException("duplicate-category-code", "Código já usado"));
    if (!parentPathIsCurrent(category))
      return Uni.createFrom().failure(new ConcurrentUpdateException());
    categories.put(category.id(), copy(category));
    return Uni.createFrom().voidItem();
  }

  public synchronized Uni<Void> update(Category category, long expectedVersion) {
    if (categories.get(category.id()).version() != expectedVersion)
      return Uni.createFrom().failure(new ConcurrentUpdateException());
    category.markPersisted(expectedVersion + 1);
    categories.put(category.id(), copy(category));
    return Uni.createFrom().voidItem();
  }

  public synchronized Uni<Integer> subtreeHeight(UUID tenantId, UUID categoryId) {
    return Uni.createFrom().item(categories.values().stream()
        .filter(c -> c.tenantId().equals(tenantId) && c.ancestry().contains(categoryId))
        .mapToInt(c -> c.ancestry().size() - c.ancestry().indexOf(categoryId)).max().orElse(0));
  }

  public synchronized Uni<Void> move(Category category, List<UUID> previousAncestry, long expectedVersion) {
    Category stored = categories.get(category.id());
    if (stored.version() != expectedVersion || !stored.ancestry().equals(previousAncestry)
        || !parentPathIsCurrent(category))
      return Uni.createFrom().failure(new ConcurrentUpdateException());
    category.markPersisted(expectedVersion + 1);
    categories.put(category.id(), copy(category));
    List<UUID> prefix = path(category);
    int kept = previousAncestry.size() + 1;
    categories.replaceAll((id, c) -> c.ancestry().contains(category.id())
        ? Category.restore(c.id(), c.tenantId(), concat(prefix, c.ancestry().subList(kept, c.ancestry().size())),
            c.code(), c.name(), c.attributes(), c.active(), c.version())
        : c);
    return Uni.createFrom().voidItem();
  }

  public synchronized Uni<Map<UUID, Integer>> childCounts(UUID tenantId, Collection<UUID> categoryIds) {
    Map<UUID, Integer> counts = new HashMap<>();
    categories.values().stream().filter(c -> c.tenantId().equals(tenantId))
        .forEach(c -> c.parentId().filter(categoryIds::contains)
            .ifPresent(parent -> counts.merge(parent, 1, Integer::sum)));
    return Uni.createFrom().item(counts);
  }

  public synchronized Uni<Map<UUID, Integer>> depths(UUID tenantId, Collection<UUID> categoryIds) {
    Map<UUID, Integer> depths = new HashMap<>();
    categoryIds.forEach(id -> Optional.ofNullable(categories.get(id)).filter(c -> c.tenantId().equals(tenantId))
        .ifPresent(c -> depths.put(id, c.ancestry().size())));
    return Uni.createFrom().item(depths);
  }

  public synchronized Uni<List<CategoryNode>> tree(UUID tenantId, Optional<String> afterPath, boolean usableOnly,
      int limit) {
    return Uni.createFrom().item(categories.values().stream().filter(c -> c.tenantId().equals(tenantId))
        .map(this::node)
        .filter(node -> !usableOnly || node.usable())
        .filter(node -> afterPath.map(after -> node.pathKey().compareTo(after) > 0).orElse(true))
        .sorted(Comparator.comparing(CategoryNode::pathKey)).limit(limit).toList());
  }

  public synchronized Uni<List<CategoryNode>> byIds(UUID tenantId, Collection<UUID> categoryIds) {
    return Uni.createFrom().item(categories.values().stream()
        .filter(c -> c.tenantId().equals(tenantId) && categoryIds.contains(c.id()))
        .sorted(Comparator.comparing(Category::code)).map(this::node).toList());
  }

  private CategoryNode node(Category c) {
    return new CategoryNode(copy(c),
        c.ancestry().stream().map(categories::get).map(CategoryNode.Ancestor::of).toList(),
        (int) categories.values().stream().filter(k -> k.parentId().equals(Optional.of(c.id()))).count());
  }

  /** Recusa a categoria com subcategoria ou produto, como as chaves estrangeiras do banco. */
  public synchronized Uni<Void> delete(Category category, long expectedVersion) {
    Category stored = categories.get(category.id());
    if (stored == null || stored.version() != expectedVersion)
      return Uni.createFrom().failure(new ConcurrentUpdateException());
    if (categories.values().stream().anyMatch(c -> c.parentId().equals(Optional.of(category.id()))))
      return Uni.createFrom().failure(CategoryNotEmptyException.hasChildren(category.code()));
    if (products.values().stream().anyMatch(p -> p.categoryId().equals(category.id())))
      return Uni.createFrom().failure(CategoryNotEmptyException.hasProducts(category.code()));
    categories.remove(category.id());
    return Uni.createFrom().voidItem();
  }

  /** Confere, como o banco, que o caminho gravado do pai é o que a categoria herdou. */
  private boolean parentPathIsCurrent(Category category) {
    return category.parentId().map(categories::get).map(parent -> path(parent).equals(category.ancestry()))
        .orElse(true);
  }

  private static List<UUID> path(Category c) {
    return concat(c.ancestry(), List.of(c.id()));
  }

  private static List<UUID> concat(List<UUID> head, List<UUID> tail) {
    List<UUID> joined = new ArrayList<>(head);
    joined.addAll(tail);
    return joined;
  }

  public synchronized Uni<Optional<Product>> findById(UUID tenantId, UUID productId) {
    return Uni.createFrom().item(Optional.ofNullable(products.get(productId))
        .filter(p -> p.tenantId().equals(tenantId)).map(InMemoryStore::copy));
  }

  public synchronized Uni<Optional<Product>> findBySku(UUID tenantId, UUID skuId) {
    return Uni.createFrom().item(products.values().stream()
        .filter(p -> p.tenantId().equals(tenantId) && p.skus().stream().anyMatch(s -> s.id().equals(skuId)))
        .findFirst().map(InMemoryStore::copy));
  }

  public synchronized Uni<Map<String, UUID>> skuIdsBySellerSku(UUID tenantId, UUID storeId,
      Collection<String> sellerSkus) {
    Map<String, UUID> found = new HashMap<>();
    products.values().stream().filter(p -> p.tenantId().equals(tenantId) && p.storeId().equals(storeId))
        .flatMap(p -> p.skus().stream()).filter(s -> sellerSkus.contains(s.sellerSku()))
        .forEach(s -> found.put(s.sellerSku(), s.id()));
    return Uni.createFrom().item(found);
  }

  public synchronized Uni<List<Product>> moderationQueue(UUID tenantId, Optional<QueuePosition> after, int limit) {
    Comparator<Product> order = Comparator.<Product, Instant>comparing(p -> p.moderationQueuedAt().orElseThrow())
        .thenComparing(p -> p.id().toString());
    return Uni.createFrom().item(products.values().stream()
        .filter(p -> p.tenantId().equals(tenantId) && p.status() == ProductStatus.PENDING_MODERATION)
        .filter(p -> after.map(a -> {
          int compared = p.moderationQueuedAt().orElseThrow().compareTo(a.queuedAt());
          return compared > 0 || compared == 0 && p.id().toString().compareTo(a.id().toString()) > 0;
        }).orElse(true))
        .sorted(order).limit(limit).map(InMemoryStore::copy).toList());
  }

  public synchronized Uni<List<Product>> search(UUID tenantId, ProductQuery query, int limit) {
    Comparator<Product> order = Comparator.<Product, Comparable<Object>>comparing(p -> sortValue(query.sort(), p))
        .thenComparing(p -> p.id().toString());
    Comparator<Product> directed = query.sort().descending() ? order.reversed() : order;
    return Uni.createFrom().item(products.values().stream()
        .filter(p -> p.tenantId().equals(tenantId))
        .filter(p -> query.storeId().map(p.storeId()::equals).orElse(true))
        .filter(p -> query.status().map(s -> effectiveStatus(p) == s).orElse(true))
        .filter(p -> query.text().map(t -> matches(p, t)).orElse(true))
        .filter(p -> query.after().map(a -> afterPosition(query.sort(), p, a)).orElse(true))
        .sorted(directed).limit(limit).map(InMemoryStore::copy).toList());
  }

  public synchronized Uni<Map<UUID, StatusChange>> lastChanges(UUID tenantId, Collection<UUID> productIds) {
    Map<UUID, StatusChange> found = new HashMap<>();
    productIds.forEach(id -> {
      List<StatusChange> changes = history.getOrDefault(id, List.of());
      if (!changes.isEmpty() && products.get(id).tenantId().equals(tenantId))
        found.put(id, changes.get(changes.size() - 1));
    });
    return Uni.createFrom().item(found);
  }

  private ProductStatus effectiveStatus(Product p) {
    return p.effectiveStatus(p.skus().stream().anyMatch(s -> stock.get(s.id()).available() > 0));
  }

  private static boolean matches(Product p, String text) {
    return ProductText.titleKey(p.title()).contains(text)
        || p.skus().stream().anyMatch(s -> s.sellerSku().toLowerCase(Locale.ROOT).contains(text));
  }

  @SuppressWarnings("unchecked")
  private static Comparable<Object> sortValue(ProductSort sort, Product p) {
    return (Comparable<Object>) (Comparable<?>) switch (sort.column()) {
      case UPDATED_AT -> p.updatedAt();
      case CREATED_AT -> p.createdAt();
      case TITLE -> ProductText.titleKey(p.title());
    };
  }

  private static boolean afterPosition(ProductSort sort, Product p, ProductQuery.Position after) {
    Comparable<Object> value = sortValue(sort, p);
    Object reference = sort.column() == ProductSort.Column.TITLE ? after.value() : Instant.parse(after.value());
    int compared = value.compareTo(reference);
    if (compared == 0)
      compared = p.id().toString().compareTo(after.id().toString());
    return sort.descending() ? compared < 0 : compared > 0;
  }

  public synchronized Uni<Void> insert(Product product) {
    Set<String> taken = new HashSet<>();
    products.values().stream().filter(p -> p.storeId().equals(product.storeId()))
        .forEach(p -> p.skus().forEach(s -> taken.add(s.sellerSku())));
    if (product.skus().stream().anyMatch(s -> taken.contains(s.sellerSku())))
      return Uni.createFrom().failure(new DuplicateException("duplicate-seller-sku", "SKU já usado na loja"));
    history.computeIfAbsent(product.id(), id -> new ArrayList<>()).addAll(product.drainChanges());
    products.put(product.id(), copy(product));
    ensureStock(product);
    return Uni.createFrom().voidItem();
  }

  public synchronized Uni<Void> update(Product product, long expectedVersion) {
    beforeProductUpdate.accept(product);
    if (products.get(product.id()).version() != expectedVersion)
      return Uni.createFrom().failure(new ConcurrentUpdateException());
    product.markPersisted(expectedVersion + 1);
    history.computeIfAbsent(product.id(), id -> new ArrayList<>()).addAll(product.drainChanges());
    Set<UUID> kept = new HashSet<>();
    product.skus().forEach(sku -> kept.add(sku.id()));
    offers.keySet().removeIf(skuId -> offers.get(skuId).productId().equals(product.id()) && !kept.contains(skuId));
    products.put(product.id(), copy(product));
    ensureStock(product);
    return Uni.createFrom().voidItem();
  }

  public synchronized Uni<List<StatusChange>> history(UUID tenantId, UUID productId) {
    return Uni.createFrom().item(List.copyOf(history.getOrDefault(productId, List.of())));
  }

  public synchronized Uni<Optional<Offer>> ofSku(UUID tenantId, UUID skuId) {
    return Uni.createFrom().item(Optional.ofNullable(offers.get(skuId)).filter(o -> o.tenantId().equals(tenantId))
        .map(InMemoryStore::copy));
  }

  public synchronized Uni<List<Offer>> ofProduct(UUID tenantId, UUID productId) {
    return Uni.createFrom()
        .item(offers.values().stream()
            .filter(o -> o.tenantId().equals(tenantId) && o.productId().equals(productId))
            .map(InMemoryStore::copy).toList());
  }

  public synchronized Uni<Map<UUID, Offer>> ofSkus(UUID tenantId, Collection<UUID> skuIds) {
    Map<UUID, Offer> found = new HashMap<>();
    skuIds.forEach(id -> Optional.ofNullable(offers.get(id)).filter(o -> o.tenantId().equals(tenantId))
        .ifPresent(o -> found.put(id, copy(o))));
    return Uni.createFrom().item(found);
  }

  public synchronized Uni<Void> insert(Offer offer) {
    if (offers.containsKey(offer.skuId()))
      return Uni.createFrom().failure(new DuplicateException("duplicate-offer", "SKU já tem oferta"));
    offer.drainEvents();
    offers.put(offer.skuId(), copy(offer));
    return Uni.createFrom().voidItem();
  }

  public synchronized Uni<Void> update(Offer offer, long expectedVersion) {
    if (offers.get(offer.skuId()).version() != expectedVersion)
      return Uni.createFrom().failure(new ConcurrentUpdateException());
    offer.markPersisted(expectedVersion + 1);
    offer.drainEvents();
    offers.put(offer.skuId(), copy(offer));
    return Uni.createFrom().voidItem();
  }

  public synchronized Uni<SellerStatus> statusOf(UUID tenantId, UUID storeId) {
    return Uni.createFrom().item(sellers.getOrDefault(storeId, SellerStatus.PENDING));
  }

  public synchronized Uni<Map<UUID, SellerStatus>> statusesOf(UUID tenantId, Collection<UUID> storeIds) {
    statusQueries++;
    Map<UUID, SellerStatus> found = new HashMap<>();
    storeIds.forEach(store -> found.put(store, sellers.getOrDefault(store, SellerStatus.PENDING)));
    return Uni.createFrom().item(found);
  }

  public synchronized Uni<SellerVerdict.Outcome> apply(String consumer, SellerVerdict verdict) {
    if (!delivered.add(consumer + ":" + verdict.eventId()))
      return Uni.createFrom().item(SellerVerdict.Outcome.DUPLICATE);
    Instant recorded = decidedAt.get(verdict.storeId());
    if (recorded != null && verdict.decidedAt().isBefore(recorded))
      return Uni.createFrom().item(SellerVerdict.Outcome.STALE);
    sellers.put(verdict.storeId(), verdict.status());
    decidedAt.put(verdict.storeId(), verdict.decidedAt());
    return Uni.createFrom().item(SellerVerdict.Outcome.APPLIED);
  }

  public synchronized Uni<Map<UUID, StockLevel>> levels(UUID tenantId, Collection<UUID> skuIds) {
    Map<UUID, StockLevel> found = new HashMap<>();
    skuIds.forEach(id -> {
      if (stock.containsKey(id))
        found.put(id, stock.get(id));
    });
    return Uni.createFrom().item(found);
  }

  public synchronized Uni<StockLevel> adjust(UUID tenantId, UUID storeId, UUID productId, UUID skuId, int onHand,
      Optional<Long> expectedVersion, String actor, Instant at) {
    StockLevel current = stock.get(skuId);
    if (current == null)
      return Uni.createFrom().failure(new NotFoundException("Estoque"));
    if (expectedVersion.isPresent() && expectedVersion.get() != current.version())
      return Uni.createFrom().failure(new VersionMismatchException(current.version()));
    if (onHand < current.reserved())
      return Uni.createFrom().failure(new StockConflictException(
          DomainReasons.STOCK_BELOW_RESERVED, "abaixo do reservado", List.of()));
    StockLevel next = new StockLevel(skuId, onHand, current.reserved(), current.sold(), current.version() + 1);
    stock.put(skuId, next);
    adjustments.add(actor + ":" + current.onHand() + "->" + onHand);
    return Uni.createFrom().item(next);
  }

  public synchronized Uni<Map<UUID, SkuCandidate>> candidates(UUID tenantId, Collection<UUID> skuIds) {
    Map<UUID, SkuCandidate> found = new HashMap<>();
    for (Product p : products.values()) {
      if (!p.tenantId().equals(tenantId))
        continue;
      for (Sku sku : p.skus()) {
        if (!skuIds.contains(sku.id()))
          continue;
        Offer offer = offers.containsKey(sku.id()) ? copy(offers.get(sku.id())) : null;
        found.put(sku.id(), new SkuCandidate(p.id(), p.storeId(), p.status(),
            sellers.getOrDefault(p.storeId(), SellerStatus.PENDING), offer));
      }
    }
    return Uni.createFrom().item(found);
  }

  public synchronized Uni<Optional<Reservation>> find(UUID tenantId, UUID id) {
    return Uni.createFrom()
        .item(Optional.ofNullable(reservations.get(id)).filter(r -> r.tenantId().equals(tenantId)));
  }

  public synchronized Uni<Optional<Reservation>> findByIdempotencyKey(UUID tenantId, String key) {
    return Uni.createFrom().item(byIdempotencyKey(tenantId, key));
  }

  public synchronized Uni<Void> insert(Reservation r) {
    if (byIdempotencyKey(r.tenantId(), r.idempotencyKey()).isPresent())
      return Uni.createFrom().failure(new DuplicateException("idempotency-conflict", "chave"));
    List<UUID> shortOf = r.items().stream().filter(i -> stock.get(i.skuId()).available() < i.quantity())
        .map(ReservationItem::skuId).toList();
    if (!shortOf.isEmpty())
      return Uni.createFrom().failure(ReservationPolicy.insufficientStock(shortOf));
    r.items().forEach(i -> move(i.skuId(), 0, i.quantity(), 0));
    reservations.put(r.id(), r);
    return Uni.createFrom().voidItem();
  }

  public synchronized Uni<Optional<Reservation>> release(UUID tenantId, UUID id, Instant at) {
    Reservation r = reservations.get(id);
    if (r == null || r.status() != ReservationStatus.ACTIVE)
      return Uni.createFrom().item(Optional.empty());
    r.items().forEach(i -> move(i.skuId(), 0, -i.quantity(), 0));
    return Uni.createFrom().item(Optional.of(close(r, ReservationStatus.RELEASED, at)));
  }

  public synchronized Uni<Optional<Reservation>> commit(UUID tenantId, UUID id, Instant at) {
    Reservation r = reservations.get(id);
    if (r == null || r.status() != ReservationStatus.ACTIVE || r.overdueAt(at))
      return Uni.createFrom().item(Optional.empty());
    r.items().forEach(i -> move(i.skuId(), -i.quantity(), -i.quantity(), i.quantity()));
    return Uni.createFrom().item(Optional.of(close(r, ReservationStatus.COMMITTED, at)));
  }

  public synchronized Uni<Optional<Reservation>> extend(UUID tenantId, UUID id, Instant newExpiry, Instant at) {
    Reservation r = reservations.get(id);
    if (r == null || r.status() != ReservationStatus.ACTIVE || r.overdueAt(at))
      return Uni.createFrom().item(Optional.empty());
    Reservation next = withTimes(r, r.status(), newExpiry, null);
    reservations.put(id, next);
    return Uni.createFrom().item(Optional.of(next));
  }

  public synchronized Uni<Integer> expireDue(Instant at, int batch) {
    List<Reservation> due = reservations.values().stream().filter(r -> r.overdueAt(at)).limit(batch).toList();
    due.forEach(r -> {
      r.items().forEach(i -> move(i.skuId(), 0, -i.quantity(), 0));
      close(r, ReservationStatus.EXPIRED, at);
    });
    return Uni.createFrom().item(due.size());
  }

  public synchronized Uni<Void> insert(ProductImport job, String content) {
    imports.put(job.id(), job);
    importContents.put(job.id(), content);
    return Uni.createFrom().voidItem();
  }

  public synchronized Uni<Optional<ProductImport>> findImport(UUID tenantId, UUID storeId, UUID id) {
    return Uni.createFrom().item(Optional.ofNullable(imports.get(id))
        .filter(j -> j.tenantId().equals(tenantId) && j.storeId().equals(storeId)));
  }

  public synchronized Uni<Optional<Summary>> findSummary(UUID tenantId, UUID storeId, UUID id) {
    return findImport(tenantId, storeId, id).map(found -> found.map(this::summary));
  }

  public synchronized Uni<List<Summary>> list(UUID tenantId, UUID storeId, Optional<Position> after, int limit) {
    Comparator<ProductImport> newestFirst = Comparator.comparing(ProductImport::createdAt)
        .thenComparing(j -> j.id().toString()).reversed();
    return Uni.createFrom().item(imports.values().stream()
        .filter(j -> j.tenantId().equals(tenantId) && j.storeId().equals(storeId))
        .filter(j -> after.map(a -> {
          int compared = j.createdAt().compareTo(a.createdAt());
          return compared < 0 || compared == 0 && j.id().toString().compareTo(a.id().toString()) < 0;
        }).orElse(true))
        .sorted(newestFirst).limit(limit).map(this::summary).toList());
  }

  private Summary summary(ProductImport job) {
    Map<ImportResult.ImportOutcome, Integer> counts = new EnumMap<>(ImportResult.ImportOutcome.class);
    importResults.getOrDefault(job.id(), new TreeMap<>()).values()
        .forEach(r -> counts.merge(r.outcome(), 1, Integer::sum));
    return new Summary(job, counts.getOrDefault(ImportResult.ImportOutcome.CREATED, 0),
        counts.getOrDefault(ImportResult.ImportOutcome.UPDATED, 0),
        counts.getOrDefault(ImportResult.ImportOutcome.UNCHANGED, 0),
        counts.getOrDefault(ImportResult.ImportOutcome.FAILED, 0));
  }

  public synchronized Uni<StoreSnapshot> store(UUID tenantId, UUID storeId, Window months, Window activity) {
    List<Product> scope = products.values().stream()
        .filter(p -> p.tenantId().equals(tenantId) && p.storeId().equals(storeId)).toList();
    Map<ProductStatus, Long> statuses = new EnumMap<>(ProductStatus.class);
    scope.forEach(p -> statuses.merge(p.status(), 1L, Long::sum));
    long outOfStock = scope.stream().filter(p -> p.status() == ProductStatus.ACTIVE
        && p.skus().stream().noneMatch(s -> stock.get(s.id()).available() > 0)).count();
    return Uni.createFrom().item(new StoreSnapshot(statuses, outOfStock, transitions(scope, months),
        transitions(scope, activity)));
  }

  public synchronized Uni<ModerationSnapshot> moderation(UUID tenantId, Window months, Window activity) {
    List<Product> scope = products.values().stream().filter(p -> p.tenantId().equals(tenantId)).toList();
    long queue = scope.stream().filter(p -> p.status() == ProductStatus.PENDING_MODERATION).count();
    return Uni.createFrom().item(new ModerationSnapshot(queue, transitions(scope, months),
        transitions(scope, activity)));
  }

  public synchronized Uni<Long> moderationQueue(UUID tenantId) {
    return Uni.createFrom().item(products.values().stream()
        .filter(p -> p.tenantId().equals(tenantId) && p.status() == ProductStatus.PENDING_MODERATION)
        .count());
  }

  public synchronized Uni<CategoryCounts> categories(UUID tenantId) {
    List<Category> scope = categories.values().stream().filter(c -> c.tenantId().equals(tenantId)).toList();
    return Uni.createFrom().item(new CategoryCounts(scope.size(),
        scope.stream().filter(c -> c.parentId().isEmpty()).count(),
        scope.stream().filter(c -> !c.active()).count(),
        scope.stream().filter(c -> products.values().stream().anyMatch(p -> p.categoryId().equals(c.id())))
            .count()));
  }

  /** Transições dos produtos de {@code scope} na janela, agrupadas como no banco. */
  private List<TransitionCount> transitions(List<Product> scope, Window window) {
    record Key(LocalDate period, ProductTransition transition, ProductStatus from, ProductStatus to) {
    }
    Map<Key, Long> counts = new LinkedHashMap<>();
    scope.forEach(p -> history.getOrDefault(p.id(), List.of()).stream()
        .filter(c -> !c.at().isBefore(window.from()) && c.at().isBefore(window.to()))
        .forEach(c -> counts.merge(new Key(window.granularity().truncate(
            LocalDate.ofInstant(c.at(), window.zone())), c.transition(), c.from(), c.to()), 1L,
            Long::sum)));
    return counts.entrySet().stream().map(e -> new TransitionCount(e.getKey().period(), e.getKey().transition(),
        e.getKey().from(), e.getKey().to(), e.getValue())).toList();
  }

  public synchronized Uni<List<ImportResult>> results(UUID importId) {
    return Uni.createFrom().item(List.copyOf(importResults.getOrDefault(importId, new TreeMap<>()).values()));
  }

  public synchronized Uni<Optional<Claimed>> claimNext(Instant now, Instant staleBefore) {
    Optional<ProductImport> next = imports.values().stream()
        .filter(j -> j.status() == ProductImport.ImportStatus.PENDING
            || j.status() == ProductImport.ImportStatus.PROCESSING && j.startedAt().isBefore(staleBefore))
        .min(Comparator.comparing(ProductImport::createdAt));
    next.ifPresent(j -> imports.put(j.id(), withStatus(j, ProductImport.ImportStatus.PROCESSING, now, null)));
    return Uni.createFrom().item(next.map(j -> new Claimed(imports.get(j.id()), importContents.get(j.id()))));
  }

  public synchronized Uni<Void> recordResults(UUID importId, List<ImportResult> results, Instant at) {
    TreeMap<Integer, ImportResult> recorded = importResults.computeIfAbsent(importId, id -> new TreeMap<>());
    results.forEach(r -> recorded.putIfAbsent(r.lineNumber(), r));
    ProductImport job = imports.get(importId);
    imports.put(importId, withStatus(job, job.status(), at, null));
    return Uni.createFrom().voidItem();
  }

  public synchronized Uni<Void> complete(UUID importId, Instant at) {
    ProductImport job = imports.get(importId);
    imports.put(importId, withStatus(job, ProductImport.ImportStatus.COMPLETED, job.startedAt(), at));
    return Uni.createFrom().voidItem();
  }

  private static ProductImport withStatus(ProductImport j, ProductImport.ImportStatus status, Instant startedAt,
      Instant finishedAt) {
    return new ProductImport(j.id(), j.tenantId(), j.storeId(), j.actor(), status, j.totalLines(), j.createdAt(),
        startedAt, finishedAt);
  }

  private void move(UUID skuId, int onHand, int reserved, int sold) {
    StockLevel s = stock.get(skuId);
    stock.put(skuId,
        new StockLevel(skuId, s.onHand() + onHand, s.reserved() + reserved, s.sold() + sold, s.version() + 1));
  }

  private Reservation close(Reservation r, ReservationStatus status, Instant at) {
    Reservation closed = withTimes(r, status, r.expiresAt(), at);
    reservations.put(r.id(), closed);
    return closed;
  }

  private static Reservation withTimes(Reservation r, ReservationStatus status, Instant expiresAt, Instant closedAt) {
    return new Reservation(r.id(), r.tenantId(), r.idempotencyKey(), r.requestHash(), r.orderRef(), status,
        r.items(), r.createdAt(), expiresAt, closedAt);
  }

  private Optional<Reservation> byIdempotencyKey(UUID tenantId, String key) {
    return reservations.values().stream()
        .filter(r -> r.tenantId().equals(tenantId) && r.idempotencyKey().equals(key)).findFirst();
  }

  public synchronized Uni<List<ModerationReasonEntry>> catalog(UUID tenantId) {
    catalogReads++;
    return Uni.createFrom().item(moderationReasons.getOrDefault(tenantId, Map.of()).values().stream()
        .sorted(Comparator.comparingInt(ModerationReasonEntry::position)
            .thenComparing(ModerationReasonEntry::code))
        .toList());
  }

  public synchronized Uni<Optional<ModerationReasonEntry>> findByCode(UUID tenantId, String code) {
    return Uni.createFrom().item(Optional.ofNullable(moderationReasons.getOrDefault(tenantId, Map.of()).get(code)));
  }

  public synchronized Uni<Void> insert(ModerationReasonEntry reason) {
    Map<String, ModerationReasonEntry> tenant = moderationReasons.computeIfAbsent(reason.tenantId(),
        t -> new HashMap<>());
    if (tenant.containsKey(reason.code()))
      return Uni.createFrom().failure(new DuplicateException("duplicate-moderation-reason-code", "Código já usado"));
    tenant.put(reason.code(), reason);
    return Uni.createFrom().voidItem();
  }

  public synchronized Uni<Void> update(ModerationReasonEntry reason, long expectedVersion) {
    Map<String, ModerationReasonEntry> tenant = moderationReasons.getOrDefault(reason.tenantId(), Map.of());
    ModerationReasonEntry current = tenant.get(reason.code());
    if (current == null || current.version() != expectedVersion)
      return Uni.createFrom().failure(new ConcurrentUpdateException());
    tenant.put(reason.code(), reason);
    return Uni.createFrom().voidItem();
  }

  private void ensureStock(Product product) {
    product.skus().forEach(sku -> stock.putIfAbsent(sku.id(), new StockLevel(sku.id(), 0, 0, 0, 0)));
  }

  static Category copy(Category c) {
    return Category.restore(c.id(), c.tenantId(), c.ancestry(), c.code(), c.name(), c.attributes(), c.active(),
        c.version());
  }

  static Product copy(Product p) {
    List<Sku> skus = p.skus().stream()
        .map(s -> Sku.create(s.id(), s.sellerSku(), s.variantAttributes(), s.physical(),
            s.dimensions().orElse(null)))
        .toList();
    return Product.restore(p.id(), p.tenantId(), p.storeId(), p.categoryId(), p.currency(), p.title(),
        p.description(),
        p.attributes(), skus, p.status(), p.version(), p.createdAt(), p.updatedAt(),
        p.moderationQueuedAt().orElse(null));
  }

  static Offer copy(Offer o) {
    return Offer.restore(o.skuId(), o.productId(), o.tenantId(), o.storeId(), o.currency(), o.price(),
        o.priceVersion(),
        o.promotions(), o.version(), o.createdAt(), o.updatedAt());
  }
}
