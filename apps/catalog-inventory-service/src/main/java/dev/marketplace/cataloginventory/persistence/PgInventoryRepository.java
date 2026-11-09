package dev.marketplace.cataloginventory.persistence;

import dev.marketplace.cataloginventory.domain.DomainReasons;
import dev.marketplace.cataloginventory.domain.Reason;
import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.application.InventoryRepository;
import dev.marketplace.cataloginventory.application.NotFoundException;
import dev.marketplace.cataloginventory.application.VersionMismatchException;
import dev.marketplace.cataloginventory.domain.inventory.InventoryEvents;
import dev.marketplace.cataloginventory.domain.inventory.SkuCandidate;
import dev.marketplace.cataloginventory.domain.inventory.StockConflictException;
import dev.marketplace.cataloginventory.domain.inventory.StockLevel;
import dev.marketplace.cataloginventory.domain.offer.Promotion;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import dev.marketplace.cataloginventory.outbox.EventOrigin;
import dev.marketplace.cataloginventory.outbox.IntegrationEvents;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
@Traced
public class PgInventoryRepository implements InventoryRepository {
        static final String LEVEL_COLUMNS = "sku_id, on_hand, reserved, sold, version";
        private static final String LEVELS = "SELECT " + LEVEL_COLUMNS
                        + " FROM inventory_level WHERE tenant_id = $1 AND sku_id = ANY ($2::uuid[])";
        private static final String LOCK_LEVEL = "SELECT " + LEVEL_COLUMNS
                        + " FROM inventory_level WHERE tenant_id = $1 AND store_id = $2 AND sku_id = $3 FOR UPDATE";
        private static final String SET_ON_HAND = """
                        UPDATE inventory_level SET on_hand = $2, version = version + 1, updated_at = $3
                        WHERE sku_id = $1""" + " RETURNING " + LEVEL_COLUMNS;
        private static final String AUDIT = """
                        INSERT INTO inventory_adjustment (id, sku_id, tenant_id, store_id, previous_on_hand, new_on_hand, actor, occurred_at)
                        VALUES ($1, $2, $3, $4, $5, $6, $7, $8)""";
        // WHY: SKU, produto, situação da loja, oferta e promoções saem de um só statement, com
        // uma linha por promoção; SKU sem oferta não tem preço para reservar.
        private static final String CANDIDATES = """
                        SELECT s.id AS candidate_id, s.product_id AS candidate_product_id, s.store_id AS candidate_store_id,
                               p.status AS product_status, coalesce(e.status, 'PENDING') AS seller_status,
                               o.sku_id, o.product_id, o.tenant_id, o.store_id, o.currency, o.list_price, o.sale_price,
                               o.price_version, o.version, o.created_at, o.updated_at,
                               pr.id AS promotion_id, pr.sale_price AS promotion_sale_price, pr.starts_at AS promotion_starts_at,
                               pr.ends_at AS promotion_ends_at
                        FROM sku s JOIN product p ON p.id = s.product_id
                        LEFT JOIN seller_eligibility e ON e.tenant_id = s.tenant_id AND e.store_id = s.store_id
                        LEFT JOIN offer o ON o.tenant_id = s.tenant_id AND o.sku_id = s.id
                        LEFT JOIN offer_promotion pr ON pr.sku_id = o.sku_id
                        WHERE s.tenant_id = $1 AND s.id = ANY ($2::uuid[])""";
        private final Pool pool;
        private final PgSupport pg;
        private final PgOutbox outbox;
        private final IntegrationEvents events;

        PgInventoryRepository(Pool pool, PgSupport pg, PgOutbox outbox, IntegrationEvents events) {
                this.pool = pool;
                this.pg = pg;
                this.outbox = outbox;
                this.events = events;
        }

        @Override
        public Uni<Map<UUID, StockLevel>> levels(UUID tenantId, Collection<UUID> skuIds) {
                if (skuIds.isEmpty())
                        return Uni.createFrom().item(Map.of());
                return pg.bounded(pool.preparedQuery(LEVELS).execute(Tuple.of(tenantId, skuIds.toArray(UUID[]::new))))
                                .map(rows -> {
                                        Map<UUID, StockLevel> levels = new HashMap<>();
                                        rows.forEach(r -> levels.put(r.getUUID("sku_id"), level(r)));
                                        return levels;
                                });
        }

        @Override
        public Uni<StockLevel> adjust(UUID tenantId, UUID storeId, UUID productId, UUID skuId, int onHand,
                        Optional<Long> expectedVersion, String actor, Instant at) {
                EventOrigin origin = EventOrigin.current();
                // WHY: FOR UPDATE serializa ajustes e reservas do mesmo SKU; o CHECK reserved
                // <= on_hand é a última barreira.
                return pg.bounded(
                                pool.withTransaction(conn -> conn.preparedQuery(LOCK_LEVEL)
                                                .execute(Tuple.of(tenantId, storeId, skuId))
                                                .flatMap(rows -> {
                                                        if (rows.size() == 0)
                                                                throw new NotFoundException("Estoque");
                                                        StockLevel current = level(rows.iterator().next());
                                                        if (expectedVersion.isPresent()
                                                                        && expectedVersion.get() != current.version())
                                                                throw new VersionMismatchException(current.version());
                                                        if (onHand < current.reserved())
                                                                throw new StockConflictException(
                                                                                DomainReasons.STOCK_BELOW_RESERVED,
                                                                                "Estoque físico não pode ficar abaixo do reservado ("
                                                                                                + current.reserved()
                                                                                                + ")",
                                                                                List.of(Reason.of(
                                                                                                DomainReasons.STOCK_BELOW_RESERVED,
                                                                                                skuId)));
                                                        return conn.preparedQuery(SET_ON_HAND)
                                                                        .execute(Tuple.of(skuId, onHand,
                                                                                        PgSupport.utc(at)))
                                                                        .map(updated -> level(
                                                                                        updated.iterator().next()))
                                                                        .call(updated -> conn.preparedQuery(AUDIT)
                                                                                        .execute(Tuple.tuple().addUUID(
                                                                                                        UUID.randomUUID())
                                                                                                        .addUUID(skuId)
                                                                                                        .addUUID(tenantId)
                                                                                                        .addUUID(storeId)
                                                                                                        .addInteger(current
                                                                                                                        .onHand())
                                                                                                        .addInteger(onHand)
                                                                                                        .addString(actor)
                                                                                                        .addValue(PgSupport
                                                                                                                        .utc(at))))
                                                                        .call(updated -> outbox.append(conn,
                                                                                        events.fromInventory(tenantId,
                                                                                                        InventoryEvents.adjusted(
                                                                                                                        current,
                                                                                                                        updated,
                                                                                                                        productId,
                                                                                                                        storeId,
                                                                                                                        at),
                                                                                                        origin)));
                                                })));
        }

        @Override
        public Uni<Map<UUID, SkuCandidate>> candidates(UUID tenantId, Collection<UUID> skuIds) {
                if (skuIds.isEmpty())
                        return Uni.createFrom().item(Map.of());
                Tuple params = Tuple.of(tenantId, skuIds.toArray(UUID[]::new));
                return pg.bounded(pool.preparedQuery(CANDIDATES).execute(params)).map(rows -> {
                        Map<UUID, Row> first = new HashMap<>();
                        Map<UUID, List<Promotion>> promotions = new HashMap<>();
                        rows.forEach(r -> {
                                UUID skuId = r.getUUID("candidate_id");
                                first.putIfAbsent(skuId, r);
                                if (r.getUUID("promotion_id") != null)
                                        promotions.computeIfAbsent(skuId, k -> new ArrayList<>())
                                                        .add(PgOfferRepository.toPromotion(r, r.getString("currency")));
                        });
                        Map<UUID, SkuCandidate> candidates = new HashMap<>();
                        first.forEach((skuId, r) -> candidates.put(skuId, new SkuCandidate(
                                        r.getUUID("candidate_product_id"), r.getUUID("candidate_store_id"),
                                        ProductStatus.valueOf(r.getString("product_status")),
                                        SellerStatus.valueOf(r.getString("seller_status")),
                                        r.getUUID("sku_id") == null ? null
                                                        : PgOfferRepository.toOffer(r,
                                                                        promotions.getOrDefault(skuId, List.of())))));
                        return candidates;
                });
        }

        static StockLevel level(Row r) {
                return new StockLevel(r.getUUID("sku_id"), r.getInteger("on_hand"), r.getInteger("reserved"),
                                r.getLong("sold"),
                                r.getLong("version"));
        }
}
