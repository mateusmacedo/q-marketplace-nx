package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.api.StatsModels.CategoryStatsResponse;
import dev.marketplace.cataloginventory.api.StatsModels.ModerationQueueResponse;
import dev.marketplace.cataloginventory.api.StatsModels.ModerationStatsResponse;
import dev.marketplace.cataloginventory.api.StatsModels.StoreStatsResponse;
import dev.marketplace.cataloginventory.application.CatalogStatsService;
import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.security.CallerContext;
import io.smallrye.mutiny.Uni;
import io.quarkus.security.PermissionsAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestQuery;
import java.util.Optional;

import static dev.marketplace.cataloginventory.security.Capabilities.MANAGE_OWN_PRODUCTS;
import static dev.marketplace.cataloginventory.security.Capabilities.MANAGE_TAXONOMY;
import static dev.marketplace.cataloginventory.security.Capabilities.MODERATE_PRODUCTS;

/**
 * Números da página inicial do painel, um endpoint por papel. Fica em {@code /stats}, longe do {@code /q/metrics} do
 * Micrometer (ADR 017), que é outra coisa: contadores do serviço para o Prometheus, sem tenant nem loja.
 */
@Path("/stats")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Stats", description = "Números da página inicial do painel, por papel")
@APIResponse(responseCode = "4XX", description = "Erro do cliente (RFC 9457)", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@APIResponse(responseCode = "5XX", description = "Erro do servidor (RFC 9457)", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@Traced(entry = true)
public class StatsResource {
    private static final String PERIOD = """
            Os baldes seguem o fuso America/Sao_Paulo. Sem `from` e `to`, que vão juntos, o período é o padrão da \
            granularidade: os últimos 30 dias até hoje (`day`), os 12 meses do ano corrente (`month`) ou os últimos 5 \
            anos até o corrente (`year`); com eles, entre 0001-01-01 e 9999-12-31, os baldes que os contêm, até 366. \
            Os indicadores trazem os últimos 7 meses, refeitos pelo histórico de transições dos produtos.""";
    private final CatalogStatsService stats;
    private final CallerContext caller;

    StatsResource(CatalogStatsService stats, CallerContext caller) {
        this.stats = stats;
        this.caller = caller;
    }

    /** Números da loja do token; 400 para granularidade, datas ou período inválidos. */
    @GET
    @Path("/store")
    @PermissionsAllowed(MANAGE_OWN_PRODUCTS)
    @Operation(operationId = "getStoreStats", summary = "Números da loja: produtos por status, indicadores e série de atividade", description = PERIOD
            + """
                     O saldo não guarda histórico: `outOfStock` só tem o valor atual, e `active` tem como valor o \
                    ACTIVE com saldo e, nos meses, o ACTIVE gravado, com ou sem saldo, sem `previous` nem `change`.""")
    @APIResponse(responseCode = "200", description = "Números da loja")
    public Uni<StoreStatsResponse> store(@Parameter(schema = @Schema(type = SchemaType.STRING, enumeration = { "day",
            "month", "year" }, defaultValue = "month")) @RestQuery String granularity,
            @Parameter(description = "Primeiro dia do período (ISO 8601)", schema = @Schema(type = SchemaType.STRING, format = "date")) @RestQuery String from,
            @Parameter(description = "Último dia do período (ISO 8601)", schema = @Schema(type = SchemaType.STRING, format = "date")) @RestQuery String to) {
        return stats.store(caller.actor(), Optional.ofNullable(granularity), QueryParams.date(from), QueryParams.date(to))
                .map(StoreStatsResponse::of);
    }

    /** Números da moderação no tenant; 400 para granularidade, datas ou período inválidos. */
    @GET
    @Path("/moderation")
    @PermissionsAllowed(MODERATE_PRODUCTS)
    @Operation(operationId = "getModerationStats", summary = "Números da moderação: fila, decisões do mês e série de atividade", description = PERIOD)
    @APIResponse(responseCode = "200", description = "Números da moderação")
    public Uni<ModerationStatsResponse> moderation(@Parameter(schema = @Schema(type = SchemaType.STRING, enumeration = {
            "day", "month", "year" }, defaultValue = "month")) @RestQuery String granularity,
            @Parameter(description = "Primeiro dia do período (ISO 8601)", schema = @Schema(type = SchemaType.STRING, format = "date")) @RestQuery String from,
            @Parameter(description = "Último dia do período (ISO 8601)", schema = @Schema(type = SchemaType.STRING, format = "date")) @RestQuery String to) {
        return stats.moderation(caller.actor(), Optional.ofNullable(granularity), QueryParams.date(from),
                QueryParams.date(to)).map(ModerationStatsResponse::of);
    }

    /** Só o tamanho atual da fila de moderação no tenant, para a leitura frequente do contador do menu. */
    @GET
    @Path("/moderation/queue")
    @PermissionsAllowed(MODERATE_PRODUCTS)
    @Operation(operationId = "getModerationQueueStats", summary = "Tamanho atual da fila de moderação", description = """
            Conta os produtos em PENDING_MODERATION agora, sem os indicadores nem a série de `/stats/moderation`, \
            para quem só mostra o tamanho da fila e o consulta com frequência.""")
    @APIResponse(responseCode = "200", description = "Tamanho da fila")
    public Uni<ModerationQueueResponse> moderationQueue() {
        return stats.moderationQueue(caller.actor()).map(ModerationQueueResponse::new);
    }

    /** Contagens da taxonomia do tenant, sem histórico. */
    @GET
    @Path("/categories")
    @PermissionsAllowed(MANAGE_TAXONOMY)
    @Operation(operationId = "getCategoryStats", summary = "Números da taxonomia: categorias, raízes, inativas e com produtos")
    @APIResponse(responseCode = "200", description = "Números da taxonomia")
    public Uni<CategoryStatsResponse> categories() {
        return stats.categories(caller.actor()).map(CategoryStatsResponse::of);
    }
}
