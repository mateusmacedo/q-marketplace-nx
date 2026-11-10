package dev.marketplace.cataloginventory.persistence;

import dev.marketplace.cataloginventory.application.CatalogStatsRepository;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import dev.marketplace.cataloginventory.domain.product.ProductTransition;
import dev.marketplace.cataloginventory.observability.Traced;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contagens da página inicial do painel (ADR 029). Cada leitura é um só
 * statement, para o estado atual e as
 * transições saírem do mesmo snapshot. Não confundir com
 * {@link PgCatalogMetrics}, dos gauges do Micrometer (ADR 017).
 */
@ApplicationScoped
@Traced
public class PgCatalogStatsRepository implements CatalogStatsRepository {
    // WHY: o balde é o date_trunc da hora local no fuso pedido, para o dia e o mês
    // virarem à meia-noite do painel. O
    // saldo sai de um LATERAL com LIMIT, que roda pelo índice de cada produto ativo
    // da loja; com NOT EXISTS, o plano
    // genérico fazia um anti join que lia o saldo do tenant inteiro (82 ms contra
    // 33 ms com 200 mil SKUs).
    private static final String STORE = """
            SELECT 'status' AS kind, NULL::date AS period, NULL::text AS transition, NULL::text AS from_status,
                   status::text AS to_status, count(*) AS n
            FROM product WHERE tenant_id = $1 AND store_id = $2 GROUP BY status
            UNION ALL
            SELECT 'out-of-stock', NULL, NULL, NULL, NULL, count(*)
            FROM product p LEFT JOIN LATERAL (
                SELECT 1 AS available FROM inventory_level l WHERE l.product_id = p.id AND l.on_hand > l.reserved LIMIT 1
            ) stock ON true
            WHERE p.tenant_id = $1 AND p.store_id = $2 AND p.status = 'ACTIVE' AND stock.available IS NULL
            UNION ALL
            SELECT 'months', (date_trunc($4, occurred_at AT TIME ZONE $3))::date, transition, from_status, to_status,
                   count(*)
            FROM product_status_change WHERE tenant_id = $1 AND store_id = $2 AND occurred_at >= $5 AND occurred_at < $6
            GROUP BY 2, 3, 4, 5
            UNION ALL
            SELECT 'activity', (date_trunc($7, occurred_at AT TIME ZONE $3))::date, transition, from_status, to_status,
                   count(*)
            FROM product_status_change WHERE tenant_id = $1 AND store_id = $2 AND occurred_at >= $8 AND occurred_at < $9
            GROUP BY 2, 3, 4, 5""";
    // WHY: os filtros vão literais para o planejador usar os índices parciais da
    // fila e das transições da moderação;
    // a moderação só precisa das entradas e saídas da fila e dos bloqueios.
    private static final String MODERATED = """
            (to_status = 'PENDING_MODERATION' OR from_status = 'PENDING_MODERATION' OR transition = 'BLOCK')""";
    private static final String MODERATION = """
            SELECT 'queue' AS kind, NULL::date AS period, NULL::text AS transition, NULL::text AS from_status,
                   NULL::text AS to_status, count(*) AS n
            FROM product WHERE tenant_id = $1 AND status = 'PENDING_MODERATION'
            UNION ALL
            SELECT 'months', (date_trunc($3, occurred_at AT TIME ZONE $2))::date, transition, from_status, to_status,
                   count(*)
            FROM product_status_change WHERE tenant_id = $1 AND occurred_at >= $4 AND occurred_at < $5 AND %1$s
            GROUP BY 2, 3, 4, 5
            UNION ALL
            SELECT 'activity', (date_trunc($6, occurred_at AT TIME ZONE $2))::date, transition, from_status, to_status,
                   count(*)
            FROM product_status_change WHERE tenant_id = $1 AND occurred_at >= $7 AND occurred_at < $8 AND %1$s
            GROUP BY 2, 3, 4, 5""".formatted(MODERATED);
    // WHY: com o status literal, a contagem roda pelo índice parcial da fila
    // (product_moderation_queue_idx).
    private static final String QUEUE = """
            SELECT count(*) AS n FROM product WHERE tenant_id = $1 AND status = 'PENDING_MODERATION'""";
    private static final String CATEGORIES = """
            SELECT count(*) AS total, count(*) FILTER (WHERE parent_id IS NULL) AS roots,
                   count(*) FILTER (WHERE NOT active) AS inactive, count(*) FILTER (WHERE in_use) AS with_products
            FROM (SELECT c.parent_id, c.active,
                         EXISTS (SELECT 1 FROM product p
                                 WHERE p.tenant_id = c.tenant_id AND p.category_id = c.id) AS in_use
                  FROM category c WHERE c.tenant_id = $1) categories""";

    private final Pool pool;
    private final PgSupport pg;

    PgCatalogStatsRepository(Pool pool, PgSupport pg) {
        this.pool = pool;
        this.pg = pg;
    }

    @Override
    public Uni<StoreSnapshot> store(UUID tenantId, UUID storeId, Window months, Window activity) {
        Tuple params = Tuple.tuple().addUUID(tenantId).addUUID(storeId).addString(months.zone().getId());
        windows(params, months, activity);
        return pg.bounded(pool.preparedQuery(STORE).execute(params)).map(rows -> {
            Map<ProductStatus, Long> statuses = new EnumMap<>(ProductStatus.class);
            long outOfStock = 0;
            for (Row row : rows) {
                switch (row.getString("kind")) {
                    case "status" -> statuses.put(ProductStatus.valueOf(row.getString("to_status")), row.getLong("n"));
                    case "out-of-stock" -> outOfStock = row.getLong("n");
                    default -> {
                    }
                }
            }
            return new StoreSnapshot(statuses, outOfStock, transitions(rows, "months"), transitions(rows, "activity"));
        });
    }

    @Override
    public Uni<ModerationSnapshot> moderation(UUID tenantId, Window months, Window activity) {
        Tuple params = Tuple.tuple().addUUID(tenantId).addString(months.zone().getId());
        windows(params, months, activity);
        return pg.bounded(pool.preparedQuery(MODERATION).execute(params)).map(rows -> {
            long queue = 0;
            for (Row row : rows)
                if ("queue".equals(row.getString("kind")))
                    queue = row.getLong("n");
            return new ModerationSnapshot(queue, transitions(rows, "months"), transitions(rows, "activity"));
        });
    }

    @Override
    public Uni<Long> moderationQueue(UUID tenantId) {
        return pg.bounded(pool.preparedQuery(QUEUE).execute(Tuple.of(tenantId)))
                .map(rows -> rows.iterator().next().getLong("n"));
    }

    @Override
    public Uni<CategoryCounts> categories(UUID tenantId) {
        return pg.bounded(pool.preparedQuery(CATEGORIES).execute(Tuple.of(tenantId))).map(rows -> {
            Row row = rows.iterator().next();
            return new CategoryCounts(row.getLong("total"), row.getLong("roots"), row.getLong("inactive"),
                    row.getLong("with_products"));
        });
    }

    /** Acrescenta granularidade, início e fim de cada janela, nesta ordem. */
    private static void windows(Tuple params, Window months, Window activity) {
        for (Window window : List.of(months, activity))
            params.addString(window.granularity().code()).addValue(PgSupport.utc(window.from()))
                    .addValue(PgSupport.utc(window.to()));
    }

    private static List<TransitionCount> transitions(RowSet<Row> rows, String kind) {
        List<TransitionCount> counts = new ArrayList<>();
        for (Row row : rows)
            if (kind.equals(row.getString("kind")))
                counts.add(new TransitionCount(row.getLocalDate("period"),
                        ProductTransition.valueOf(row.getString("transition")),
                        ProductStatus.valueOf(row.getString("from_status")),
                        ProductStatus.valueOf(row.getString("to_status")), row.getLong("n")));
        return counts;
    }
}
