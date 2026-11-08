package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.category.Category;
import dev.marketplace.cataloginventory.domain.inventory.*;
import dev.marketplace.cataloginventory.domain.offer.Offer;
import dev.marketplace.cataloginventory.domain.product.*;
import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * Repositórios em memória que guardam cópias, como um banco, e aplicam o
 * controle otimista de versão.
 */
final class InMemoryStore
        implements CategoryRepository, ProductRepository, OfferRepository, SellerEligibilityRepository,
        InventoryRepository, ReservationRepository, ProductImportRepository {
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
    /** Roda no início de cada gravação de produto; o teste o usa para simular outro escritor. */
    Consumer<Product> beforeProductUpdate = product -> {};

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

    public synchronized Uni<List<Product>> find(UUID tenantId, Optional<UUID> storeId, Optional<ProductStatus> status,
            Optional<String> afterId, int limit) {
        return Uni.createFrom().item(products.values().stream()
                .filter(p -> p.tenantId().equals(tenantId))
                .filter(p -> storeId.map(p.storeId()::equals).orElse(true))
                .filter(p -> status.map(s -> p.status() == s).orElse(true))
                .filter(p -> afterId.map(a -> p.id().toString().compareTo(a) > 0).orElse(true))
                .sorted(Comparator.comparing(p -> p.id().toString())).limit(limit).map(InMemoryStore::copy).toList());
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
                    "stock-below-reserved", "abaixo do reservado", List.of()));
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
                p.attributes(), skus, p.status(), p.version(), p.createdAt(), p.updatedAt());
    }

    static Offer copy(Offer o) {
        return Offer.restore(o.skuId(), o.productId(), o.tenantId(), o.storeId(), o.currency(), o.price(),
                o.priceVersion(),
                o.promotions(), o.version(), o.createdAt(), o.updatedAt());
    }
}
