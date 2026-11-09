package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.application.CatalogStatsRepository.CategoryCounts;
import dev.marketplace.cataloginventory.application.CatalogStatsService;
import dev.marketplace.cataloginventory.application.CatalogStatsService.Activity;
import dev.marketplace.cataloginventory.application.CatalogStatsService.Indicator;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Contratos HTTP dos números da página inicial do painel. */
public final class StatsModels {
    private StatsModels() {
    }

    public record MonthValueResponse(@Schema(description = "Primeiro dia do mês") LocalDate month,
            @Schema(description = "Valor no fim do mês, ou agora no mês corrente; ausente quando não há histórico", nullable = true) Long value) {
    }

    @Schema(description = "Indicador com o valor atual, a variação em relação ao fim do mês anterior e os últimos 7 meses")
    public record IndicatorResponse(long value,
            @Schema(description = "Valor no fim do mês anterior; ausente quando não há histórico", nullable = true) Long previous,
            @Schema(description = "value menos previous; ausente quando não há histórico", nullable = true) Long change,
            @Schema(description = "Do mês mais antigo ao corrente") List<MonthValueResponse> months) {
        static IndicatorResponse of(Indicator indicator) {
            return new IndicatorResponse(indicator.value(), indicator.previous().orElse(null),
                    indicator.change().orElse(null), indicator.months().stream()
                            .map(m -> new MonthValueResponse(m.month(), m.value().orElse(null))).toList());
        }
    }

    @Schema(description = "Transições num balde da série")
    public record ActivityPointResponse(@Schema(description = "Início do balde") LocalDate period,
            @Schema(description = "Entradas na fila de moderação: envio, edição de produto publicado e desbloqueio") long submitted,
            long approved, long rejected, long blocked) {
    }

    public record ActivityResponse(
            @Schema(enumeration = { "day", "month", "year" }) String granularity,
            @Schema(description = "Primeiro dia do primeiro balde") LocalDate from,
            @Schema(description = "Último dia do último balde") LocalDate to,
            @Schema(description = "Fuso dos baldes") String timeZone,
            @Schema(description = "Um ponto por balde, inclusive sem transição") List<ActivityPointResponse> points) {
        static ActivityResponse of(Activity activity) {
            return new ActivityResponse(activity.granularity().code(), activity.from(), activity.to(),
                    CatalogStatsService.ZONE.getId(), activity.points().stream()
                            .map(p -> new ActivityPointResponse(p.period(), p.submitted(), p.approved(), p.rejected(),
                                    p.blocked()))
                            .toList());
        }
    }

    @Schema(description = "Produtos da loja por status, como a resposta do produto o mostra")
    public record StatusCountsResponse(long draft, long pendingModeration,
            @Schema(description = "ACTIVE com saldo disponível") long active,
            @Schema(description = "ACTIVE sem saldo disponível") long outOfStock, long pausedBySeller, long blocked,
            long archived,
            @Schema(description = "Todos os produtos da loja menos os arquivados") long total) {
        static StatusCountsResponse of(Map<ProductStatus, Long> counts, long total) {
            return new StatusCountsResponse(counts.get(ProductStatus.DRAFT),
                    counts.get(ProductStatus.PENDING_MODERATION), counts.get(ProductStatus.ACTIVE),
                    counts.get(ProductStatus.OUT_OF_STOCK), counts.get(ProductStatus.PAUSED_BY_SELLER),
                    counts.get(ProductStatus.BLOCKED), counts.get(ProductStatus.ARCHIVED), total);
        }
    }

    public record StoreStatsResponse(UUID storeId, StatusCountsResponse counts,
            @Schema(description = "Valor: ACTIVE com saldo, como counts.active. Meses: o ACTIVE gravado, com ou sem saldo, no fim de cada mês e agora no corrente. Sem previous nem change, porque o saldo não guarda passado") IndicatorResponse active,
            IndicatorResponse draft,
            @Schema(description = "Sem histórico: o saldo não guarda passado, então só o mês corrente tem valor") IndicatorResponse outOfStock,
            IndicatorResponse blocked, ActivityResponse activity) {
        static StoreStatsResponse of(CatalogStatsService.StoreStats stats) {
            return new StoreStatsResponse(stats.storeId(), StatusCountsResponse.of(stats.counts(), stats.total()),
                    IndicatorResponse.of(stats.active()), IndicatorResponse.of(stats.draft()),
                    IndicatorResponse.of(stats.outOfStock()), IndicatorResponse.of(stats.blocked()),
                    ActivityResponse.of(stats.activity()));
        }
    }

    public record ModerationStatsResponse(
            @Schema(description = "Produtos em PENDING_MODERATION; nos meses anteriores, no fim do mês") IndicatorResponse queue,
            @Schema(description = "Aprovações no mês") IndicatorResponse approved,
            @Schema(description = "Rejeições no mês") IndicatorResponse rejected,
            @Schema(description = "Bloqueios no mês") IndicatorResponse blocked, ActivityResponse activity) {
        static ModerationStatsResponse of(CatalogStatsService.ModerationStats stats) {
            return new ModerationStatsResponse(IndicatorResponse.of(stats.queue()),
                    IndicatorResponse.of(stats.approved()), IndicatorResponse.of(stats.rejected()),
                    IndicatorResponse.of(stats.blocked()), ActivityResponse.of(stats.activity()));
        }
    }

    @Schema(description = "Tamanho atual da fila de moderação, sem histórico")
    public record ModerationQueueResponse(@Schema(description = "Produtos em PENDING_MODERATION no tenant") long count) {
    }

    public record CategoryStatsResponse(long total, @Schema(description = "Categorias sem pai") long roots,
            @Schema(description = "Categorias desativadas, sem contar as que só têm um ancestral inativo") long inactive,
            @Schema(description = "Categorias com algum produto, inclusive arquivado") long withProducts) {
        static CategoryStatsResponse of(CategoryCounts counts) {
            return new CategoryStatsResponse(counts.total(), counts.roots(), counts.inactive(), counts.withProducts());
        }
    }
}
