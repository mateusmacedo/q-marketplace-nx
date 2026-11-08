package dev.marketplace.cataloginventory.persistence;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.observability.Telemetry;
import dev.marketplace.cataloginventory.application.ProductRepository;
import dev.marketplace.cataloginventory.domain.inventory.StockConflictException;
import dev.marketplace.cataloginventory.domain.product.*;
import dev.marketplace.cataloginventory.messaging.EventOrigin;
import dev.marketplace.cataloginventory.messaging.IntegrationEvents;
import dev.marketplace.cataloginventory.messaging.OutboxMessage;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import io.vertx.pgclient.PgException;
import jakarta.enterprise.context.ApplicationScoped;
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
        private static final String PRODUCT_COLUMNS = "id, tenant_id, store_id, category_id, currency, title, description, attributes, status, version, created_at, updated_at";
        private static final String INSERT_PRODUCT = """
                        INSERT INTO product (id, tenant_id, store_id, category_id, currency, title, description, attributes, status, version,
                                             created_at, updated_at)
                        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, 0, $10, $11)""";
        private static final String UPDATE_PRODUCT = """
                        UPDATE product SET category_id = $3, title = $4, description = $5, attributes = $6, status = $7,
                                           version = version + 1, updated_at = $8
                        WHERE tenant_id = $1 AND id = $2 AND version = $9""";
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
                        INSERT INTO product_status_change (id, product_id, tenant_id, transition, from_status, to_status, actor, reason, occurred_at)
                        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)""";
        // WHY: produto e SKUs vêm do mesmo statement, cujo snapshot impede misturar SKUs
        // de versões diferentes; o LIMIT da listagem fica na seleção dos produtos, antes do JOIN.
        private static final String AGGREGATE = """
                        WITH selected AS (%s)
                        SELECT selected.*, s.id AS sku_id, s.seller_sku, s.variant_attributes, s.physical, s.height_cm, s.width_cm,
                               s.length_cm, s.weight_g
                        FROM selected LEFT JOIN sku s ON s.product_id = selected.id
                        ORDER BY selected.id, s.position""";
        private static final String SELECT_HISTORY = """
                        SELECT id, transition, from_status, to_status, actor, reason, occurred_at FROM product_status_change
                        WHERE tenant_id = $1 AND product_id = $2 ORDER BY occurred_at, id""";
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
        public Uni<List<Product>> find(UUID tenantId, Optional<UUID> storeId, Optional<ProductStatus> status,
                        Optional<String> afterId, int limit) {
                StringBuilder sql = new StringBuilder(
                                "SELECT " + PRODUCT_COLUMNS + " FROM product WHERE tenant_id = $1");
                List<Object> params = new ArrayList<>(List.of(tenantId));
                storeId.ifPresent(s -> {
                        params.add(s);
                        sql.append(" AND store_id = $").append(params.size());
                });
                status.ifPresent(s -> {
                        params.add(s.name());
                        sql.append(" AND status = $").append(params.size());
                });
                afterId.ifPresent(a -> {
                        params.add(UUID.fromString(a));
                        sql.append(" AND id > $").append(params.size());
                });
                params.add(limit);
                sql.append(" ORDER BY id LIMIT $").append(params.size());
                return query(sql.toString(), Tuple.from(params));
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
                                .addValue(PgSupport.utc(product.updatedAt()));
                UUID[] skuIds = skuIds(product);
                return pg.bounded(pool.withTransaction(conn -> conn.preparedQuery(INSERT_PRODUCT).execute(row)
                                .flatMap(ignored -> writeChildren(conn, product, changes, skuIds))
                                .flatMap(ignored -> outbox.append(conn, messages))))
                                .onFailure().transform(PgProductRepository::duplicateSellerSku);
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
                                .addLong(expectedVersion);
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
                                .invoke(() -> product.markPersisted(expectedVersion + 1));
        }

        @Override
        public Uni<List<StatusChange>> history(UUID tenantId, UUID productId) {
                return pg.bounded(pool.preparedQuery(SELECT_HISTORY).execute(Tuple.of(tenantId, productId)))
                                .map(rows -> StreamSupport.stream(rows.spliterator(), false)
                                                .map(r -> new StatusChange(r.getUUID("id"),
                                                                ProductTransition.valueOf(r.getString("transition")),
                                                                ProductStatus.valueOf(r.getString("from_status")),
                                                                ProductStatus.valueOf(r.getString("to_status")),
                                                                r.getString("actor"), r.getString("reason"),
                                                                PgSupport.instant(r.getOffsetDateTime("occurred_at"))))
                                                .toList());
        }

        /**
         * Lê os produtos escolhidos por {@code productSql} junto com os SKUs, num
         * único statement, e monta os agregados, no prazo do banco.
         */
        private Uni<List<Product>> query(String productSql, Tuple params) {
                return pg.bounded(pool.preparedQuery(AGGREGATE.formatted(productSql)).execute(params))
                                .map(PgProductRepository::assemble);
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
                                .addString(c.actor()).addString(c.reason()).addValue(PgSupport.utc(c.at()))).toList();
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
                                PgSupport.instant(r.getOffsetDateTime("updated_at")));
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
                        return new StockConflictException("sku-has-stock",
                                        "SKU com estoque ou reserva não pode ser removido; zere o estoque antes",
                                        List.of());
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
