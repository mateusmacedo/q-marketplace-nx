package dev.marketplace.cataloginventory.persistence;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.observability.Lifecycle;
import dev.marketplace.cataloginventory.observability.Telemetry;
import dev.marketplace.cataloginventory.application.ReservationRepository;
import dev.marketplace.cataloginventory.domain.Money;
import dev.marketplace.cataloginventory.domain.inventory.*;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.*;
import dev.marketplace.cataloginventory.messaging.EventOrigin;
import dev.marketplace.cataloginventory.messaging.IntegrationEvents;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * Reservas como transações atômicas. Saldos são
 * alterados em ordem de SKU para que
 * reservas concorrentes travem linhas na mesma sequência; transições de estado
 * são compare-and-set sobre o status.
 */
@ApplicationScoped
@Traced
public class PgReservationRepository implements ReservationRepository {
        private static final String COLUMNS = "id, tenant_id, idempotency_key, request_hash, order_ref, status, created_at, expires_at, closed_at";
        private static final String INSERT = """
                        INSERT INTO inventory_reservation (id, tenant_id, idempotency_key, request_hash, order_ref, status, created_at, expires_at)
                        VALUES ($1, $2, $3, $4, $5, 'ACTIVE', $6, $7)""";
        private static final String INSERT_ITEM = """
                        INSERT INTO inventory_reservation_item (reservation_id, sku_id, product_id, store_id, quantity, currency, unit_price, price_version)
                        VALUES ($1, $2, $3, $4, $5, $6, $7, $8)""";
        private static final String RESERVE_STOCK = """
                        UPDATE inventory_level SET reserved = reserved + $3, version = version + 1, updated_at = $4
                        WHERE tenant_id = $1 AND sku_id = $2 AND on_hand - reserved >= $3""" + " RETURNING "
                        + PgInventoryRepository.LEVEL_COLUMNS;
        private static final String RETURN_STOCK = """
                        UPDATE inventory_level SET reserved = reserved - $2, version = version + 1, updated_at = $3
                        WHERE sku_id = $1""" + " RETURNING " + PgInventoryRepository.LEVEL_COLUMNS;
        private static final String CONSUME_STOCK = """
                        UPDATE inventory_level SET on_hand = on_hand - $2, reserved = reserved - $2, sold = sold + $2, version = version + 1,
                                                   updated_at = $3
                        WHERE sku_id = $1"""
                        + " RETURNING " + PgInventoryRepository.LEVEL_COLUMNS;
        private static final String CLOSE = "UPDATE inventory_reservation SET status = $3, closed_at = $4"
                        + " WHERE tenant_id = $1 AND id = $2 AND status = 'ACTIVE'";
        private static final String COMMIT = CLOSE + " AND expires_at > $4 RETURNING " + COLUMNS;
        private static final String RELEASE = CLOSE + " RETURNING " + COLUMNS;
        private static final String EXTEND = "UPDATE inventory_reservation SET expires_at = $3"
                        + " WHERE tenant_id = $1 AND id = $2 AND status = 'ACTIVE' AND expires_at > $4 RETURNING "
                        + COLUMNS;
        private static final String DUE = "SELECT tenant_id, id FROM inventory_reservation"
                        + " WHERE status = 'ACTIVE' AND expires_at <= $1 ORDER BY expires_at LIMIT $2";
        private static final String EXPIRE = CLOSE + " AND expires_at <= $4 RETURNING " + COLUMNS;
        private static final String ITEMS = """
                        SELECT reservation_id, sku_id, product_id, store_id, quantity, currency, unit_price, price_version
                        FROM inventory_reservation_item WHERE reservation_id = ANY ($1::uuid[]) ORDER BY reservation_id, sku_id""";
        private final Pool pool;
        private final PgSupport pg;
        private final PgOutbox outbox;
        private final Lifecycle lifecycle;
        private final IntegrationEvents events;

        PgReservationRepository(Pool pool, PgSupport pg, PgOutbox outbox, IntegrationEvents events,
                        Lifecycle lifecycle) {
                this.pool = pool;
                this.lifecycle = lifecycle;
                this.pg = pg;
                this.outbox = outbox;
                this.events = events;
        }

        @Override
        public Uni<Optional<Reservation>> find(UUID tenantId, UUID id) {
                String sql = "SELECT " + COLUMNS + " FROM inventory_reservation WHERE tenant_id = $1 AND id = $2";
                return pg.bounded(withItems(pool, pool.preparedQuery(sql).execute(Tuple.of(tenantId, id))))
                                .map(PgReservationRepository::first);
        }

        @Override
        public Uni<Optional<Reservation>> findByIdempotencyKey(UUID tenantId, String key) {
                String sql = "SELECT " + COLUMNS
                                + " FROM inventory_reservation WHERE tenant_id = $1 AND idempotency_key = $2";
                return pg.bounded(withItems(pool, pool.preparedQuery(sql).execute(Tuple.of(tenantId, key))))
                                .map(PgReservationRepository::first);
        }

        @Override
        public Uni<Void> insert(Reservation r) {
                // WHY: o id nasce no servidor e não está no caminho do POST; a tag liga a criação às requisições
                // seguintes da reserva (ADR 021).
                Telemetry.tag("marketplace.reservation.id", r.id());
                Telemetry.tag("marketplace.order.ref", r.orderRef());
                EventOrigin origin = EventOrigin.current();
                Tuple header = Tuple.tuple().addUUID(r.id()).addUUID(r.tenantId()).addString(r.idempotencyKey())
                                .addString(r.requestHash())
                                .addString(r.orderRef()).addValue(PgSupport.utc(r.createdAt()))
                                .addValue(PgSupport.utc(r.expiresAt()));
                List<Tuple> items = r.items().stream()
                                .map(i -> Tuple.tuple().addUUID(r.id()).addUUID(i.skuId()).addUUID(i.productId())
                                                .addUUID(i.storeId()).addInteger(i.quantity())
                                                .addString(i.unitPrice().currency())
                                                .addValue(i.unitPrice().amount())
                                                .addLong(i.priceVersion()))
                                .toList();
                return pg.bounded(pool.withTransaction(conn -> conn.preparedQuery(INSERT).execute(header)
                                .flatMap(ignored -> conn.preparedQuery(INSERT_ITEM).executeBatch(items))
                                .flatMap(ignored -> reserveAll(conn, r))
                                .flatMap(levels -> outbox.append(conn,
                                                events.fromInventory(r.tenantId(), InventoryEvents.reserved(r, levels),
                                                                origin)))))
                                .onFailure()
                                .transform(e -> PgSupport.unique(e, "reservation_idempotency_uk",
                                                "idempotency-conflict",
                                                "Idempotency-Key já em uso"));
        }

        @Override
        public Uni<Optional<Reservation>> release(UUID tenantId, UUID id, Instant at) {
                return transition(tenantId, id, RELEASE, ReservationStatus.RELEASED, at, RETURN_STOCK,
                                EventOrigin.current());
        }

        @Override
        public Uni<Optional<Reservation>> commit(UUID tenantId, UUID id, Instant at) {
                return transition(tenantId, id, COMMIT, ReservationStatus.COMMITTED, at, CONSUME_STOCK,
                                EventOrigin.current());
        }

        @Override
        public Uni<Optional<Reservation>> extend(UUID tenantId, UUID id, Instant newExpiry, Instant at) {
                return pg.bounded(withItems(pool, pool.preparedQuery(EXTEND)
                                .execute(Tuple.of(tenantId, id, PgSupport.utc(newExpiry), PgSupport.utc(at)))))
                                .map(PgReservationRepository::first);
        }

        @Override
        public Uni<Integer> expireDue(Instant at, int batch) {
                // WHY: cada reserva expira na própria transação e trava o saldo na ordem canônica dos SKUs, como as
                // demais operações, o que evita deadlock; o UPDATE condicional (status ACTIVE e prazo vencido)
                // impede que dois pods expirem a mesma reserva.
                // WHY: EventOrigin.current() roda dentro da etapa para que o evento da expiração saia no trace dela; o
                // tenant entra na etapa porque a consulta DUE cruza tenants.
                return pg.bounded(pool.preparedQuery(DUE).execute(Tuple.of(PgSupport.utc(at), batch)))
                                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                                .onItem().transformToUniAndConcatenate(row -> lifecycle.step("reservation expire", () -> {
                                                        Telemetry.tag(Telemetry.TENANT, row.getUUID("tenant_id"));
                                                        Telemetry.tag("marketplace.reservation.id", row.getUUID("id"));
                                                        return transition(row.getUUID("tenant_id"), row.getUUID("id"),
                                                                        EXPIRE, ReservationStatus.EXPIRED, at,
                                                                        RETURN_STOCK, EventOrigin.current());
                                                }))
                                .filter(Optional::isPresent)
                                .collect().asList().map(List::size);
        }

        /**
         * Encerra a reserva ativa por compare-and-set sobre o status, aplica o ajuste
         * de saldo a cada item, na ordem canônica, e grava o evento na outbox, na mesma
         * transação. Devolve vazio, sem tocar no estoque, quando a reserva já não está
         * ativa ou o prazo não confere (confirmação vencida, expiração antecipada).
         */
        private Uni<Optional<Reservation>> transition(UUID tenantId, UUID id, String sql, ReservationStatus target,
                        Instant at, String stockSql,
                        EventOrigin origin) {
                return pg.bounded(pool.withTransaction(conn -> withItems(conn, conn.preparedQuery(sql)
                                .execute(Tuple.of(tenantId, id, target.name(), PgSupport.utc(at))))
                                .flatMap(changed -> changed.isEmpty()
                                                ? Uni.createFrom().item(Optional.<Reservation>empty())
                                                : applyToStock(conn, changed.get(0), stockSql, at)
                                                                .flatMap(levels -> outbox.append(conn,
                                                                                events.fromInventory(tenantId,
                                                                                                InventoryEvents.closed(
                                                                                                                changed.get(0),
                                                                                                                target,
                                                                                                                levels,
                                                                                                                at),
                                                                                                origin)))
                                                                .replaceWith(Optional.of(changed.get(0))))));
        }

        /**
         * Soma o reservado de todos os itens; o item sem linha alterada não tinha
         * saldo, e a falta em qualquer SKU desfaz a transação inteira. Devolve os
         * novos saldos.
         */
        private static Uni<Map<UUID, StockLevel>> reserveAll(SqlConnection conn, Reservation r) {
                return updateInLockOrder(conn, RESERVE_STOCK, r.items(), i -> Tuple.of(r.tenantId(), i.skuId(),
                                i.quantity(), PgSupport.utc(r.createdAt())))
                                .map(results -> {
                                        List<UUID> missing = results.stream().filter(e -> e.getValue().rowCount() != 1)
                                                        .map(e -> e.getKey().skuId())
                                                        .toList();
                                        if (!missing.isEmpty())
                                                throw ReservationPolicy.insufficientStock(missing);
                                        Map<UUID, StockLevel> levels = new HashMap<>();
                                        results.forEach(e -> levels.put(e.getKey().skuId(),
                                                        PgInventoryRepository.level(e.getValue().iterator().next())));
                                        return levels;
                                });
        }

        /**
         * Aplica a mudança de saldo a cada item da reserva e devolve os novos
         * saldos.
         */
        private static Uni<Map<UUID, StockLevel>> applyToStock(SqlConnection conn, Reservation r, String sql,
                        Instant at) {
                return updateInLockOrder(conn, sql, r.items(), i -> Tuple.of(i.skuId(), i.quantity(), PgSupport.utc(at)))
                                .map(results -> {
                                        Map<UUID, StockLevel> bySku = new HashMap<>();
                                        results.forEach(e -> {
                                                StockLevel level = PgInventoryRepository
                                                                .level(e.getValue().iterator().next());
                                                bySku.put(level.skuId(), level);
                                        });
                                        return bySku;
                                });
        }

        /**
         * Executa {@code sql} para todos os itens num único lote, na ordem canônica
         * de travamento, e devolve o resultado de cada item nessa ordem.
         */
        private static Uni<List<Map.Entry<ReservationItem, RowSet<Row>>>> updateInLockOrder(SqlConnection conn,
                        String sql, Collection<ReservationItem> items, Function<ReservationItem, Tuple> params) {
                List<ReservationItem> ordered = ReservationPolicy.inLockOrder(items);
                if (ordered.isEmpty())
                        return Uni.createFrom().item(List.of());
                // WHY: o lote segue numa só ida ao banco, e o PostgreSQL executa os UPDATEs na
                // ordem da lista, então as travas de linha continuam na ordem canônica.
                return conn.preparedQuery(sql).executeBatch(ordered.stream().map(params).toList()).map(first -> {
                        List<Map.Entry<ReservationItem, RowSet<Row>>> results = new ArrayList<>();
                        RowSet<Row> result = first;
                        for (ReservationItem item : ordered) {
                                results.add(Map.entry(item, result));
                                result = result.next();
                        }
                        return results;
                });
        }

        /**
         * Carrega numa segunda consulta os itens das reservas das linhas e monta as
         * reservas, sem
         * N+1.
         */
        private static Uni<List<Reservation>> withItems(SqlClient client, Uni<RowSet<Row>> headers) {
                return headers.flatMap(rows -> {
                        List<Row> found = new ArrayList<>();
                        rows.forEach(found::add);
                        if (found.isEmpty())
                                return Uni.createFrom().item(List.<Reservation>of());
                        UUID[] ids = found.stream().map(r -> r.getUUID("id")).toArray(UUID[]::new);
                        return client.preparedQuery(ITEMS).execute(Tuple.of((Object) ids)).map(itemRows -> {
                                Map<UUID, List<ReservationItem>> byReservation = new HashMap<>();
                                itemRows.forEach(i -> byReservation
                                                .computeIfAbsent(i.getUUID("reservation_id"), k -> new ArrayList<>())
                                                .add(new ReservationItem(i.getUUID("sku_id"), i.getUUID("product_id"),
                                                                i.getUUID("store_id"),
                                                                i.getInteger("quantity"),
                                                                new Money(i.getBigDecimal("unit_price"),
                                                                                i.getString("currency")),
                                                                i.getLong("price_version"))));
                                return found.stream()
                                                .map(r -> new Reservation(r.getUUID("id"), r.getUUID("tenant_id"),
                                                                r.getString("idempotency_key"),
                                                                r.getString("request_hash"), r.getString("order_ref"),
                                                                ReservationStatus.valueOf(r.getString("status")),
                                                                byReservation.getOrDefault(r.getUUID("id"), List.of()),
                                                                PgSupport.instant(r.getOffsetDateTime("created_at")),
                                                                PgSupport.instant(r.getOffsetDateTime("expires_at")),
                                                                PgSupport.instant(r.getOffsetDateTime("closed_at"))))
                                                .toList();
                        });
                });
        }

        private static Optional<Reservation> first(List<Reservation> found) {
                return found.stream().findFirst();
        }
}
