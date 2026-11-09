package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import dev.marketplace.cataloginventory.domain.product.ProductTransition;
import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Contagens da página inicial do painel, tiradas do estado atual e do histórico de transições dos produtos. */
public interface CatalogStatsRepository {
    /** Intervalo semiaberto {@code [from, to)} contado em baldes da granularidade no fuso {@code zone}. */
    record Window(StatsGranularity granularity, ZoneId zone, Instant from, Instant to) {
    }

    /** Transições gravadas num balde, pelo início dele no fuso, com a origem e o destino. */
    record TransitionCount(LocalDate period, ProductTransition transition, ProductStatus from, ProductStatus to,
            long count) {
    }

    /**
     * Retrato da loja: o estado gravado dos produtos (sem {@code OUT_OF_STOCK}, que não é gravado), quantos
     * {@code ACTIVE} estão sem saldo disponível e as transições por balde de cada janela.
     */
    record StoreSnapshot(Map<ProductStatus, Long> statuses, long outOfStock, List<TransitionCount> months,
            List<TransitionCount> activity) {
    }

    /**
     * Retrato do tenant para a moderação: o tamanho da fila e, por balde de cada janela, ao menos as transições que
     * entram na fila, saem dela ou bloqueiam.
     */
    record ModerationSnapshot(long queue, List<TransitionCount> months, List<TransitionCount> activity) {
    }

    /** Categorias do tenant: todas, as raízes, as inativas e as que têm algum produto, inclusive arquivado. */
    record CategoryCounts(long total, long roots, long inactive, long withProducts) {
    }

    /** Lê tudo num só snapshot do banco; as duas janelas usam o mesmo fuso. */
    Uni<StoreSnapshot> store(UUID tenantId, UUID storeId, Window months, Window activity);

    /** Lê tudo num só snapshot do banco; as duas janelas usam o mesmo fuso. */
    Uni<ModerationSnapshot> moderation(UUID tenantId, Window months, Window activity);

    /** Produtos em {@code PENDING_MODERATION} no tenant agora, sem ler o histórico de transições. */
    Uni<Long> moderationQueue(UUID tenantId);

    Uni<CategoryCounts> categories(UUID tenantId);
}
