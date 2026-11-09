package dev.marketplace.cataloginventory.persistence;

import dev.marketplace.cataloginventory.domain.DomainReasons;
import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.observability.Telemetry;
import dev.marketplace.cataloginventory.application.ConcurrentUpdateException;
import dev.marketplace.cataloginventory.application.ProductQuery;
import dev.marketplace.cataloginventory.application.ProductRepository;
import dev.marketplace.cataloginventory.application.ProductSort;
import dev.marketplace.cataloginventory.application.ProductText;
import dev.marketplace.cataloginventory.domain.inventory.StockConflictException;
import dev.marketplace.cataloginventory.domain.product.*;
import dev.marketplace.cataloginventory.outbox.EventOrigin;
import dev.marketplace.cataloginventory.outbox.IntegrationEvents;
import dev.marketplace.cataloginventory.outbox.OutboxMessage;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import io.vertx.pgclient.PgException;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.StreamSupport;

/**
 * Persiste o agregado de produto numa transação: linha do produto (com versão),
 * SKUs, saldo inicial e histórico.
 */
@ApplicationScoped
@Traced
public class PgProductRepository implements ProductRepository {
        private static final String FOREIGN_KEY_VIOLATION = "23503";
        private static final String PRODUCT_COLUMNS = "id, tenant_id, store_id, category_id, currency, title, description, attributes, status, version, created_at, updated_at, moderation_queued_at";
        private static final String INSERT_PRODUCT = """
                        INSERT INTO product (id, tenant_id, store_id, category_id, currency, title, description, attributes, status, version,
                                             created_at, updated_at, title_key, moderation_queued_at)
                        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, 0, $10, $11, $12, $13)""";
        private static final String UPDATE_PRODUCT = """
                        UPDATE product SET category_id = $3, title = $4, description = $5, attributes = $6, status = $7,
                                           version = version + 1, updated_at = $8, title_key = $10, moderation_queued_at = $11
                        WHERE tenant_id = $1 AND id = $2 AND version = $9""";
        // WHY: o estado vai literal para o planejador usar o índice parcial da fila, que só cobre PENDING_MODERATION.
        private static final String SELECT_QUEUE = "SELECT " + PRODUCT_COLUMNS + """
                         FROM product WHERE tenant_id = $1 AND status = 'PENDING_MODERATION'%s
                        ORDER BY moderation_queued_at, id LIMIT $%d""";
        // WHY: só some o saldo zerado; se restar estoque ou reserva, a FK sem cascata
        // barra a remoção do SKU. A oferta do SKU
        // removido sai em cascata.
        private static final String DELETE_EMPTY_INVENTORY = """
                        DELETE FROM inventory_level WHERE product_id = $1 AND sku_id <> ALL ($2::uuid[]) AND on_hand = 0 AND reserved = 0""";
        private static final String DELETE_REMOVED_SKUS = "DELETE FROM sku WHERE product_id = $1 AND id <> ALL ($2::uuid[])";
        private static final String CREATE_INVENTORY = """
                        INSERT INTO inventory_level (sku_id, product_id, tenant_id, store_id) SELECT unnest($1::uuid[]), $2, $3, $4
                        ON CONFLICT (sku_id) DO NOTHING""";
        // WHY: o WHERE evita reescrever o SKU que não mudou; a linha conflitante
        // continua travada até o fim da transação.
        private static final String UPSERT_SKU = """
                        INSERT INTO sku (id, product_id, tenant_id, store_id, position, seller_sku, variant_attributes, physical, height_cm,
                                         width_cm, length_cm, weight_g)
                        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
                        ON CONFLICT (id) DO UPDATE SET position = EXCLUDED.position, variant_attributes = EXCLUDED.variant_attributes,
                            physical = EXCLUDED.physical, height_cm = EXCLUDED.height_cm, width_cm = EXCLUDED.width_cm,
                            length_cm = EXCLUDED.length_cm, weight_g = EXCLUDED.weight_g
                        WHERE (sku.position, sku.variant_attributes, sku.physical, sku.height_cm, sku.width_cm,
                               sku.length_cm, sku.weight_g)
                            IS DISTINCT FROM (EXCLUDED.position, EXCLUDED.variant_attributes, EXCLUDED.physical,
                                              EXCLUDED.height_cm, EXCLUDED.width_cm, EXCLUDED.length_cm, EXCLUDED.weight_g)""";
        private static final String INSERT_CHANGE = """
                        INSERT INTO product_status_change (id, product_id, tenant_id, transition, from_status, to_status, actor, note, occurred_at,
                                                           reason_codes, reason_labels, store_id)
                        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)""";
        // WHY: produto e SKUs vêm do mesmo statement, cujo snapshot impede misturar SKUs
        // de versões diferentes; o LIMIT da listagem fica na seleção dos produtos, antes do JOIN.
        private static final String AGGREGATE = """
                        WITH selected AS (%s)
                        SELECT selected.*, s.id AS sku_id, s.seller_sku, s.variant_attributes, s.physical, s.height_cm, s.width_cm,
                               s.length_cm, s.weight_g
                        FROM selected LEFT JOIN sku s ON s.product_id = selected.id
                        ORDER BY %s, s.position""";
        private static final String CHANGE_COLUMNS = "id, transition, from_status, to_status, actor, note, reason_labels, occurred_at";
        private static final String SELECT_HISTORY = "SELECT " + CHANGE_COLUMNS + """
                         FROM product_status_change
                        WHERE tenant_id = $1 AND product_id = $2 ORDER BY occurred_at, id""";
        private static final String SELECT_LAST_CHANGES = "SELECT DISTINCT ON (product_id) product_id, " + CHANGE_COLUMNS
                        + """
                         FROM product_status_change
                        WHERE tenant_id = $1 AND product_id = ANY ($2::uuid[]) ORDER BY product_id, occurred_at DESC, id DESC""";
        // WHY: disponível como no ProductResponse (on_hand - reserved > 0); OUT_OF_STOCK é o ACTIVE sem nenhum SKU assim.
        private static final String HAS_AVAILABLE = """
                        EXISTS (SELECT 1 FROM inventory_level l WHERE l.product_id = p.id AND l.on_hand > l.reserved)""";
        // WHY: com o OR, busca seletiva junta os índices trigram (título e, pelo id, o código do SKU) num BitmapOr, e
        // busca ampla percorre o índice da ordem sem calcular os SKUs (o ARRAY só roda se o título não casa); o IN
        // sobre uma união calculava a união inteira, e 200 mil casamentos levavam 1 s.
        private static final String MATCHES_TEXT = """
                        (p.title_key LIKE $%1$d OR p.id = ANY (ARRAY(
                            SELECT s.product_id FROM sku s WHERE s.tenant_id = $1%2$s AND lower(s.seller_sku) LIKE $%1$d)))""";
        private static final String SELECT_SKU_IDS = """
                        SELECT seller_sku, id FROM sku WHERE tenant_id = $1 AND store_id = $2 AND seller_sku = ANY ($3::varchar[])""";

        private final Pool pool;
        private final PgSupport pg;
        private final PgOutbox outbox;
        private final IntegrationEvents events;

        PgProductRepository(Pool pool, PgSupport pg, PgOutbox outbox, IntegrationEvents events) {
                this.pool = pool;
                this.pg = pg;
                this.outbox = outbox;
                this.events = events;
        }

        @Override
        public Uni<Optional<Product>> findById(UUID tenantId, UUID productId) {
                String sql = "SELECT " + PRODUCT_COLUMNS + " FROM product WHERE tenant_id = $1 AND id = $2";
                return query(sql, Tuple.of(tenantId, productId)).map(found -> found.stream().findFirst());
        }

        @Override
        public Uni<Optional<Product>> findBySku(UUID tenantId, UUID skuId) {
                String sql = "SELECT " + PRODUCT_COLUMNS + " FROM product WHERE tenant_id = $1"
                                + " AND id = (SELECT product_id FROM sku WHERE tenant_id = $1 AND id = $2)";
                return query(sql, Tuple.of(tenantId, skuId)).map(found -> found.stream().findFirst());
        }

        @Override
        public Uni<Map<String, UUID>> skuIdsBySellerSku(UUID tenantId, UUID storeId, Collection<String> sellerSkus) {
                if (sellerSkus.isEmpty())
                        return Uni.createFrom().item(Map.of());
                Tuple params = Tuple.of(tenantId, storeId, sellerSkus.toArray(String[]::new));
                return pg.bounded(pool.preparedQuery(SELECT_SKU_IDS).execute(params)).map(rows -> {
                        Map<String, UUID> ids = new HashMap<>();
                        rows.forEach(r -> ids.put(r.getString("seller_sku"), r.getUUID("id")));
                        return ids;
                });
        }

        @Override
        public Uni<List<Product>> moderationQueue(UUID tenantId, Optional<QueuePosition> after, int limit) {
                List<Object> params = new ArrayList<>(List.of(tenantId));
                String position = after.map(a -> {
                        params.add(PgSupport.utc(a.queuedAt()));
                        params.add(a.id());
                        return " AND (moderation_queued_at, id) > ($2, $3)";
                }).orElse("");
                params.add(limit);
                return query(SELECT_QUEUE.formatted(position, params.size()),
                                "selected.moderation_queued_at, selected.id", Tuple.from(params));
        }

        @Override
        public Uni<List<Product>> search(UUID tenantId, ProductQuery query, int limit) {
                List<Object> params = new ArrayList<>(List.of(tenantId));
                List<String> conditions = new ArrayList<>(List.of("p.tenant_id = $1"));
                StringBuilder skuStore = new StringBuilder();
                query.storeId().ifPresent(s -> {
                        params.add(s);
                        conditions.add("p.store_id = $" + params.size());
                        skuStore.append(" AND s.store_id = $").append(params.size());
                });
                query.status().ifPresent(s -> conditions.add(switch (s) {
                        case ACTIVE -> "p.status = 'ACTIVE' AND " + HAS_AVAILABLE;
                        case OUT_OF_STOCK -> "p.status = 'ACTIVE' AND NOT " + HAS_AVAILABLE;
                        default -> {
                                params.add(s.name());
                                yield "p.status = $" + params.size();
                        }
                }));
                query.text().ifPresent(t -> {
                        params.add("%" + escapeLike(t) + "%");
                        conditions.add(MATCHES_TEXT.formatted(params.size(), skuStore));
                });
                String column = switch (query.sort().column()) {
                        case UPDATED_AT -> "updated_at";
                        case CREATED_AT -> "created_at";
                        case TITLE -> "title_key";
                };
                boolean descending = query.sort().descending();
                query.after().ifPresent(position -> {
                        params.add(query.sort().column() == ProductSort.Column.TITLE ? position.value()
                                        : PgSupport.utc(Instant.parse(position.value())));
                        params.add(position.id());
                        conditions.add("(p.%s, p.id) %s ($%d, $%d)".formatted(column, descending ? "<" : ">",
                                        params.size() - 1, params.size()));
                });
                params.add(limit);
                String direction = descending ? " DESC" : " ASC";
                String products = "SELECT " + PRODUCT_COLUMNS + ", title_key FROM product p WHERE "
                                + String.join(" AND ", conditions) + " ORDER BY p." + column + direction + ", p.id"
                                + direction + " LIMIT $" + params.size();
                String order = "selected." + column + direction + ", selected.id" + direction;
                if (query.text().isEmpty())
                        return query(products, order, Tuple.from(params));
                // WHY: no plano genérico, que o PostgreSQL pode adotar depois de 5 execuções do statement guardado na
                // conexão, o ramo do SKU percorre o ARRAY item a item em cada produto descartado (39 s com 200 mil
                // produtos); o customizado mantém o BitmapOr da busca seletiva e o ARRAY calculado uma vez.
                return pg.customPlan(pool, AGGREGATE.formatted(products, order), Tuple.from(params))
                                .map(PgProductRepository::assemble);
        }

        @Override
        public Uni<Map<UUID, StatusChange>> lastChanges(UUID tenantId, Collection<UUID> productIds) {
                if (productIds.isEmpty())
                        return Uni.createFrom().item(Map.of());
                Tuple params = Tuple.of(tenantId, productIds.toArray(UUID[]::new));
                return pg.bounded(pool.preparedQuery(SELECT_LAST_CHANGES).execute(params)).map(rows -> {
                        Map<UUID, StatusChange> changes = new HashMap<>();
                        rows.forEach(r -> changes.put(r.getUUID("product_id"), toChange(r)));
                        return changes;
                });
        }

        @Override
        public Uni<Void> insert(Product product) {
                List<StatusChange> changes = product.drainChanges();
                // WHY: o id nasce no servidor e não está no caminho do POST; a tag liga a criação às requisições
                // seguintes do produto (ADR 021).
                Telemetry.tag("marketplace.product.id", product.id());
                List<OutboxMessage> messages = events.fromProduct(product.tenantId(), product.drainEvents(),
                                EventOrigin.current());
                Tuple row = Tuple.tuple().addUUID(product.id()).addUUID(product.tenantId()).addUUID(product.storeId())
                                .addUUID(product.categoryId()).addString(product.currency()).addString(product.title())
                                .addString(product.description())
                                .addValue(PgSupport.json(product.attributes())).addString(product.status().name())
                                .addValue(PgSupport.utc(product.createdAt()))
                                .addValue(PgSupport.utc(product.updatedAt()))
                                .addString(ProductText.titleKey(product.title()))
                                .addValue(queuedAt(product));
                UUID[] skuIds = skuIds(product);
                return pg.bounded(pool.withTransaction(conn -> conn.preparedQuery(INSERT_PRODUCT).execute(row)
                                .flatMap(ignored -> writeChildren(conn, product, changes, skuIds))
                                .flatMap(ignored -> outbox.append(conn, messages))))
                                .onFailure().transform(PgProductRepository::duplicateSellerSku)
                                .onFailure().transform(PgProductRepository::categoryRemoved);
        }

        @Override
        public Uni<Void> update(Product product, long expectedVersion) {
                List<StatusChange> changes = product.drainChanges();
                List<OutboxMessage> messages = events.fromProduct(product.tenantId(), product.drainEvents(),
                                EventOrigin.current());
                Tuple row = Tuple.tuple().addUUID(product.tenantId()).addUUID(product.id())
                                .addUUID(product.categoryId())
                                .addString(product.title()).addString(product.description())
                                .addValue(PgSupport.json(product.attributes()))
                                .addString(product.status().name()).addValue(PgSupport.utc(product.updatedAt()))
                                .addLong(expectedVersion).addString(ProductText.titleKey(product.title()))
                                .addValue(queuedAt(product));
                UUID[] skuIds = skuIds(product);
                return pg.bounded(pool.withTransaction(conn -> conn.preparedQuery(UPDATE_PRODUCT).execute(row)
                                .invoke(PgSupport::requireOneRow)
                                .flatMap(ignored -> conn.preparedQuery(DELETE_EMPTY_INVENTORY)
                                                .execute(Tuple.of(product.id(), skuIds)))
                                .flatMap(ignored -> conn.preparedQuery(DELETE_REMOVED_SKUS)
                                                .execute(Tuple.of(product.id(), skuIds)))
                                .flatMap(ignored -> writeChildren(conn, product, changes, skuIds))
                                .flatMap(ignored -> outbox.append(conn, messages))))
                                .onFailure().transform(PgProductRepository::duplicateSellerSku)
                                .onFailure().transform(PgProductRepository::skuWithStock)
                                .onFailure().transform(PgProductRepository::categoryRemoved)
                                .invoke(() -> product.markPersisted(expectedVersion + 1));
        }

        @Override
        public Uni<List<StatusChange>> history(UUID tenantId, UUID productId) {
                return pg.bounded(pool.preparedQuery(SELECT_HISTORY).execute(Tuple.of(tenantId, productId)))
                                .map(rows -> StreamSupport.stream(rows.spliterator(), false)
                                                .map(PgProductRepository::toChange).toList());
        }

        private static StatusChange toChange(Row r) {
                return new StatusChange(r.getUUID("id"), ProductTransition.valueOf(r.getString("transition")),
                                ProductStatus.valueOf(r.getString("from_status")),
                                ProductStatus.valueOf(r.getString("to_status")),
                                r.getString("actor"), labels(r.getJsonArray("reason_labels")), r.getString("note"),
                                PgSupport.instant(r.getOffsetDateTime("occurred_at")));
        }

        /** Rótulos da época gravados com a decisão ({@code [{code, label}]}). */
        static List<ReasonLabel> labels(JsonArray array) {
                List<ReasonLabel> labels = new ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                        JsonObject label = array.getJsonObject(i);
                        labels.add(new ReasonLabel(label.getString("code"), label.getString("label")));
                }
                return labels;
        }

        static JsonArray json(List<ReasonLabel> labels) {
                JsonArray array = new JsonArray();
                labels.forEach(l -> array.add(new JsonObject().put("code", l.code()).put("label", l.label())));
                return array;
        }

        private static OffsetDateTime queuedAt(Product product) {
                return product.moderationQueuedAt().map(PgSupport::utc).orElse(null);
        }

        /**
         * Lê os produtos escolhidos por {@code productSql} junto com os SKUs, num
         * único statement, e monta os agregados em ordem de id, no prazo do banco.
         */
        private Uni<List<Product>> query(String productSql, Tuple params) {
                return query(productSql, "selected.id", params);
        }

        /** Como {@link #query(String, Tuple)}, na ordem {@code order} sobre as colunas de {@code selected}. */
        private Uni<List<Product>> query(String productSql, String order, Tuple params) {
                return pg.bounded(pool.preparedQuery(AGGREGATE.formatted(productSql, order)).execute(params))
                                .map(PgProductRepository::assemble);
        }

        /** Escapa os curingas do LIKE ({@code %}, {@code _} e a barra de escape) no texto buscado. */
        private static String escapeLike(String text) {
                return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        }

        /**
         * Grava os SKUs com upsert por id, na ordem do agregado, cria o saldo zerado
         * dos SKUs novos sem
         * tocar nos existentes e anexa as transições ao histórico, tudo na transação da
         * conexão.
         */
        private Uni<Void> writeChildren(SqlConnection conn, Product product, List<StatusChange> changes,
                        UUID[] skuIds) {
                List<Sku> skus = product.skus();
                List<Tuple> skuRows = new ArrayList<>();
                for (int position = 0; position < skus.size(); position++) {
                        Sku s = skus.get(position);
                        Dimensions d = s.dimensions().orElse(null);
                        skuRows.add(Tuple.tuple().addUUID(s.id()).addUUID(product.id()).addUUID(product.tenantId())
                                        .addUUID(product.storeId())
                                        .addInteger(position).addString(s.sellerSku())
                                        .addValue(PgSupport.json(s.variantAttributes()))
                                        .addBoolean(s.physical()).addValue(d == null ? null : d.heightCm())
                                        .addValue(d == null ? null : d.widthCm())
                                        .addValue(d == null ? null : d.lengthCm())
                                        .addValue(d == null ? null : d.weightGrams()));
                }
                List<Tuple> changeRows = changes.stream().map(c -> Tuple.tuple().addUUID(c.id()).addUUID(product.id())
                                .addUUID(product.tenantId()).addString(c.transition().name()).addString(c.from().name())
                                .addString(c.to().name())
                                .addString(c.actor()).addString(c.note()).addValue(PgSupport.utc(c.at()))
                                .addArrayOfString(c.reasonCodes().toArray(String[]::new))
                                .addValue(json(c.reasons()))
                                .addUUID(product.storeId())).toList();
                return PgSupport.batch(conn, UPSERT_SKU, skuRows)
                                .flatMap(ignored -> skuIds.length == 0 ? Uni.createFrom().voidItem()
                                                : conn.preparedQuery(CREATE_INVENTORY)
                                                                .execute(Tuple.of(skuIds, product.id(),
                                                                                product.tenantId(), product.storeId()))
                                                                .replaceWithVoid())
                                .flatMap(ignored -> PgSupport.batch(conn, INSERT_CHANGE, changeRows));
        }

        private static UUID[] skuIds(Product product) {
                return product.skus().stream().map(Sku::id).toArray(UUID[]::new);
        }

        /**
         * Monta os agregados na ordem das linhas, que trazem o produto repetido em
         * cada SKU, ou uma vez sem SKU.
         */
        private static List<Product> assemble(RowSet<Row> rows) {
                Map<UUID, Row> products = new LinkedHashMap<>();
                Map<UUID, List<Sku>> skusByProduct = new HashMap<>();
                rows.forEach(r -> {
                        UUID id = r.getUUID("id");
                        products.putIfAbsent(id, r);
                        List<Sku> skus = skusByProduct.computeIfAbsent(id, k -> new ArrayList<>());
                        if (r.getUUID("sku_id") != null)
                                skus.add(toSku(r));
                });
                return products.entrySet().stream()
                                .map(e -> toProduct(e.getValue(), skusByProduct.get(e.getKey())))
                                .toList();
        }

        /**
         * Reconstrói o agregado persistido a partir da linha e dos SKUs já carregados.
         */
        private static Product toProduct(Row r, List<Sku> skus) {
                return Product.restore(r.getUUID("id"), r.getUUID("tenant_id"), r.getUUID("store_id"),
                                r.getUUID("category_id"),
                                r.getString("currency"), r.getString("title"), r.getString("description"),
                                PgSupport.strings(r.getJsonObject("attributes")), skus,
                                ProductStatus.valueOf(r.getString("status")),
                                r.getLong("version"), PgSupport.instant(r.getOffsetDateTime("created_at")),
                                PgSupport.instant(r.getOffsetDateTime("updated_at")),
                                PgSupport.instant(r.getOffsetDateTime("moderation_queued_at")));
        }

        /** Reconstrói o SKU; as dimensões só são lidas quando o SKU é físico. */
        private static Sku toSku(Row r) {
                Dimensions dimensions = r.getBoolean("physical")
                                ? new Dimensions(r.getBigDecimal("height_cm"), r.getBigDecimal("width_cm"),
                                                r.getBigDecimal("length_cm"),
                                                r.getInteger("weight_g"))
                                : null;
                return Sku.create(r.getUUID("sku_id"), r.getString("seller_sku"),
                                PgSupport.strings(r.getJsonObject("variant_attributes")),
                                r.getBoolean("physical"), dimensions);
        }

        /**
         * Traduz a violação da FK do saldo (SKU removido ainda com estoque ou reserva)
         * em {@code
         * sku-has-stock}; demais falhas seguem inalteradas.
         */
        private static Throwable skuWithStock(Throwable failure) {
                if (failure instanceof PgException pg && FOREIGN_KEY_VIOLATION.equals(pg.getSqlState())
                                && "inventory_level_sku_fk".equals(pg.getConstraint()))
                        return new StockConflictException(DomainReasons.SKU_HAS_STOCK,
                                        "SKU com estoque ou reserva não pode ser removido; zere o estoque antes",
                                        List.of());
                return failure;
        }

        /**
         * Traduz a chave estrangeira da categoria, excluída depois que o caso de uso a leu, em conflito de
         * atualização; demais falhas seguem inalteradas.
         */
        private static Throwable categoryRemoved(Throwable failure) {
                if (failure instanceof PgException pg && FOREIGN_KEY_VIOLATION.equals(pg.getSqlState())
                                && "product_category_fk".equals(pg.getConstraint()))
                        return new ConcurrentUpdateException();
                return failure;
        }

        /**
         * Traduz a unicidade do SKU do seller na loja em {@code duplicate-seller-sku};
         * demais falhas
         * seguem inalteradas.
         */
        private static Throwable duplicateSellerSku(Throwable failure) {
                return PgSupport.unique(failure, "sku_store_seller_sku_uk", "duplicate-seller-sku",
                                "SKU do seller já usado em outro produto da loja");
        }
}
