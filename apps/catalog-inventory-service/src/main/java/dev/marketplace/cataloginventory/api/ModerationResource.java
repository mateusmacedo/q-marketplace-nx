package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.api.ProductModels.*;
import dev.marketplace.cataloginventory.application.ModerationService;
import dev.marketplace.cataloginventory.application.Page;
import dev.marketplace.cataloginventory.security.CallerContext;
import dev.marketplace.cataloginventory.security.Roles;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestHeader;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;
import java.util.UUID;

/**
 * Trust &amp; Safety: fila de moderação e decisões sobre produtos do tenant.
 */
@Path("/moderation/products")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.CATALOG_MODERATOR)
@Tag(name = "Moderation", description = "Trust & Safety: fila, aprovação, rejeição e bloqueio de produtos")
@APIResponse(responseCode = "4XX", description = "Erro do cliente (RFC 9457)", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@APIResponse(responseCode = "5XX", description = "Erro do servidor (RFC 9457)", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@Traced(entry = true)
public class ModerationResource {
    private final ModerationService moderation;
    private final ProductResponses responses;
    private final CallerContext caller;
    private final Counter approved;
    private final Counter rejected;
    private final Counter blocked;
    private final Counter unblocked;

    ModerationResource(ModerationService moderation, ProductResponses responses, CallerContext caller,
            MeterRegistry registry) {
        this.moderation = moderation;
        this.responses = responses;
        this.caller = caller;
        this.approved = decisions(registry, "approved");
        this.rejected = decisions(registry, "rejected");
        this.blocked = decisions(registry, "blocked");
        this.unblocked = decisions(registry, "unblocked");
    }

    /** Contador {@code catalog.moderation.decisions} da decisão, base da taxa de rejeição (ADR 017). */
    private static Counter decisions(MeterRegistry registry, String decision) {
        return Counter.builder("catalog.moderation.decisions")
                .description("Decisões de moderação concluídas, por tipo")
                .tag("decision", decision).register(registry);
    }

    /**
     * Lista os produtos em {@code PENDING_MODERATION} do tenant, com cursor opaco
     * por id; cursor
     * malformado responde 400.
     */
    @GET
    @Operation(operationId = "moderationQueue", summary = "Produtos aguardando moderação")
    @APIResponse(responseCode = "200", description = "Página da fila")
    public Uni<ProductPage> queue(@RestQuery String cursor,
            @RestQuery @DefaultValue("" + Page.DEFAULT_SIZE) @Min(1) @Max(Page.MAX_SIZE) int limit) {
        return moderation.queue(caller.actor(), Cursors.decodeUuid(cursor), limit).flatMap(responses::page);
    }

    /**
     * Ativa o produto em moderação; {@code If-Match} opcional (412 se divergir).
     * Responde 409 se a
     * transição não é permitida no estado atual e 422, com os motivos em
     * {@code reasons}, se a
     * política de publicação não é atendida.
     */
    @POST
    @Path("/{id}/approve")
    @Operation(operationId = "approveProduct", summary = "Aprova produto (exige oferta em todo SKU e seller com KYC aprovado)")
    @APIResponse(responseCode = "200", description = "Produto ativo")
    public Uni<RestResponse<ProductResponse>> approve(@RestPath UUID id, @RestHeader("If-Match") String ifMatch) {
        return moderation.approve(caller.actor(), id, ETags.optional(ifMatch))
                .invoke(() -> approved.increment())
                .flatMap(responses::ok);
    }

    /**
     * Devolve o produto ao rascunho e registra o motivo no histórico;
     * {@code If-Match} opcional
     * (412 se divergir) e 409 se a transição não é permitida no estado atual.
     */
    @POST
    @Path("/{id}/reject")
    @Operation(operationId = "rejectProduct", summary = "Rejeita e devolve ao rascunho com motivo")
    @APIResponse(responseCode = "200", description = "Produto em rascunho")
    public Uni<RestResponse<ProductResponse>> reject(@RestPath UUID id, @RestHeader("If-Match") String ifMatch,
            @NotNull @Valid ReasonRequest request) {
        return moderation.reject(caller.actor(), id, ETags.optional(ifMatch), request.reason())
                .invoke(() -> rejected.increment())
                .flatMap(responses::ok);
    }

    /**
     * Bloqueia o produto e registra o motivo no histórico; {@code If-Match}
     * opcional (412 se
     * divergir) e 409 se a transição não é permitida no estado atual.
     */
    @POST
    @Path("/{id}/block")
    @Operation(operationId = "blockProduct", summary = "Bloqueia produto; sai da vitrine imediatamente")
    @APIResponse(responseCode = "200", description = "Produto bloqueado")
    public Uni<RestResponse<ProductResponse>> block(@RestPath UUID id, @RestHeader("If-Match") String ifMatch,
            @NotNull @Valid ReasonRequest request) {
        return moderation.block(caller.actor(), id, ETags.optional(ifMatch), request.reason())
                .invoke(() -> blocked.increment())
                .flatMap(responses::ok);
    }

    /**
     * Devolve o produto bloqueado à fila de moderação; {@code If-Match} opcional
     * (412 se divergir)
     * e 409 se ele não está bloqueado.
     */
    @POST
    @Path("/{id}/unblock")
    @Operation(operationId = "unblockProduct", summary = "Desbloqueia e devolve à fila de moderação")
    @APIResponse(responseCode = "200", description = "Produto em moderação")
    public Uni<RestResponse<ProductResponse>> unblock(@RestPath UUID id, @RestHeader("If-Match") String ifMatch) {
        return moderation.unblock(caller.actor(), id, ETags.optional(ifMatch))
                .invoke(() -> unblocked.increment())
                .flatMap(responses::ok);
    }
}
