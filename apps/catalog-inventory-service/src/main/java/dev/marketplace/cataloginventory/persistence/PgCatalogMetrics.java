package dev.marketplace.cataloginventory.persistence;

import dev.marketplace.cataloginventory.observability.SkuCountsSource;
import dev.marketplace.cataloginventory.observability.Traced;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Contagens do marketplace inteiro, sem filtro de tenant, para as métricas de
 * negócio (ADR 017). Fica no serviço, sem porta na aplicação, porque só os
 * gauges a consomem.
 */
@ApplicationScoped
@Traced
public class PgCatalogMetrics implements SkuCountsSource {
    // WHY: loja sem linha em seller_eligibility ainda está PENDING, como na leitura
    // da elegibilidade.
    private static final String SKU_COUNTS = """
            SELECT count(*) AS active,
                   count(*) FILTER (WHERE coalesce(e.status, 'PENDING') = 'APPROVED'
                                    AND i.on_hand - i.reserved > 0) AS sellable
            FROM sku s
            JOIN product p ON p.id = s.product_id
            LEFT JOIN seller_eligibility e ON e.tenant_id = p.tenant_id AND e.store_id = p.store_id
            LEFT JOIN inventory_level i ON i.sku_id = s.id
            WHERE p.status = 'ACTIVE'""";

    private final Pool pool;

    PgCatalogMetrics(Pool pool) {
        this.pool = pool;
    }

    /**
     * Conta os SKUs ativos e os vendáveis de todos os tenants numa única consulta
     * agregada.
     */
    @Override
    public Uni<SkuCounts> skuCounts() {
        return pool.query(SKU_COUNTS).execute().map(rows -> {
            Row r = rows.iterator().next();
            return new SkuCounts(r.getLong("active"), r.getLong("sellable"));
        });
    }
}
