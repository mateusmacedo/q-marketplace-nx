package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.api.BatchModels.BatchItemRequest;
import dev.marketplace.cataloginventory.api.ModerationModels.*;
import dev.marketplace.cataloginventory.api.ProductModels.*;
import dev.marketplace.cataloginventory.application.Actor;
import dev.marketplace.cataloginventory.application.Batch;
import dev.marketplace.cataloginventory.application.ModerationDecision;
import dev.marketplace.cataloginventory.application.ModerationService;
import dev.marketplace.cataloginventory.application.Page;
import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.security.CallerContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.mutiny.Uni;
import io.quarkus.security.PermissionsAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestHeader;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.marketplace.cataloginventory.security.Capabilities.MODERATE_PRODUCTS;

/**
 * Trust &amp; Safety: fila de moderação e decisões sobre produtos do tenant;
 * o catálogo de motivos fica em {@link ModerationReasonResource}.
 */
@Path("/moderation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@PermissionsAllowed(MODERATE_PRODUCTS)
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
    private final Map<ModerationDecision, Counter> byDecision;

    ModerationResource(ModerationService moderation, ProductResponses responses, CallerContext caller,
            MeterRegistry registry) {
        this.moderation = moderation;
        this.responses = responses;
        this.caller = caller;
        this.approved = decisions(registry, "approved");
        this.rejected = decisions(registry, "rejected");
        this.blocked = decisions(registry, "blocked");
        this.unblocked = decisions(registry, "unblocked");
        this.byDecision = Map.of(ModerationDecision.APPROVE, approved, ModerationDecision.REJECT, rejected,
                ModerationDecision.BLOCK, blocked);
    }

    /**
     * Contador {@code catalog.moderation.decisions} da decisão, base da taxa de
     * rejeição (ADR 017).
     */
    private static Counter decisions(MeterRegistry registry, String decision) {
        return Counter.builder("catalog.moderation.decisions")
                .description("Decisões de moderação concluídas, por tipo")
                .tag("decision", decision).register(registry);
    }

    /**
     * Lista os produtos em {@code PENDING_MODERATION} do tenant, do que entrou
     * na fila há mais tempo ao mais recente, com cursor opaco; cursor
     * malformado responde 400.
     */
    @GET
    @Path("/products")
    @Operation(operationId = "moderationQueue", summary = "Produtos aguardando moderação, em ordem de chegada")
    @APIResponse(responseCode = "200", description = "Página da fila")
    public Uni<ProductPage> queue(@RestQuery String cursor,
            @RestQuery @DefaultValue("" + Page.DEFAULT_SIZE) @Min(1) @Max(Page.MAX_SIZE) int limit) {
        Actor actor = caller.actor();
        return moderation.queue(actor, Cursors.decode(cursor), limit).flatMap(page -> responses.page(actor, page));
    }

    /**
     * Ativa o produto em moderação; {@code If-Match} opcional (412 se divergir).
     * Responde 409 se a
     * transição não é permitida no estado atual e 422, com os motivos em
     * {@code reasons}, se a
     * política de publicação não é atendida.
     */
    @POST
    @Path("/products/{id}/approve")
    @Operation(operationId = "approveProduct", summary = "Aprova produto (exige oferta em todo SKU e seller com KYC aprovado)")
    @APIResponse(responseCode = "200", description = "Produto ativo")
    public Uni<RestResponse<ProductResponse>> approve(@RestPath UUID id, @RestHeader("If-Match") String ifMatch) {
        Actor actor = caller.actor();
        return moderation.approve(actor, id, ETags.optional(ifMatch))
                .invoke(() -> approved.increment())
                .flatMap(product -> responses.savedOk(actor, product));
    }

    /**
     * Devolve o produto ao rascunho e registra no histórico os motivos, com o
     * rótulo da época, e o complemento; motivo fora do catálogo, desativado ou
     * vazio responde 422, {@code If-Match} divergente 412 e transição negada 409.
     */
    @POST
    @Path("/products/{id}/reject")
    @Operation(operationId = "rejectProduct", summary = "Rejeita e devolve ao rascunho com motivos prontos e complemento")
    @APIResponse(responseCode = "200", description = "Produto em rascunho")
    public Uni<RestResponse<ProductResponse>> reject(@RestPath UUID id, @RestHeader("If-Match") String ifMatch,
            @NotNull @Valid DecisionRequest request) {
        Actor actor = caller.actor();
        return moderation.reject(actor, id, ETags.optional(ifMatch), request.codes(), request.noteText())
                .invoke(() -> rejected.increment())
                .flatMap(product -> responses.savedOk(actor, product));
    }

    /**
     * Bloqueia o produto e registra o motivo como na rejeição; motivo fora do
     * catálogo do bloqueio ou vazio responde 422, {@code If-Match} divergente
     * 412 e transição negada 409.
     */
    @POST
    @Path("/products/{id}/block")
    @Operation(operationId = "blockProduct", summary = "Bloqueia produto; sai da vitrine imediatamente")
    @APIResponse(responseCode = "200", description = "Produto bloqueado")
    public Uni<RestResponse<ProductResponse>> block(@RestPath UUID id, @RestHeader("If-Match") String ifMatch,
            @NotNull @Valid DecisionRequest request) {
        Actor actor = caller.actor();
        return moderation.block(actor, id, ETags.optional(ifMatch), request.codes(), request.noteText())
                .invoke(() -> blocked.increment())
                .flatMap(product -> responses.savedOk(actor, product));
    }

    /**
     * Devolve o produto bloqueado à fila de moderação; {@code If-Match} opcional
     * (412 se divergir)
     * e 409 se ele não está bloqueado.
     */
    @POST
    @Path("/products/{id}/unblock")
    @Operation(operationId = "unblockProduct", summary = "Desbloqueia e devolve à fila de moderação")
    @APIResponse(responseCode = "200", description = "Produto em moderação")
    public Uni<RestResponse<ProductResponse>> unblock(@RestPath UUID id, @RestHeader("If-Match") String ifMatch) {
        Actor actor = caller.actor();
        return moderation.unblock(actor, id, ETags.optional(ifMatch))
                .invoke(() -> unblocked.increment())
                .flatMap(product -> responses.savedOk(actor, product));
    }

    /**
     * Aplica a decisão a cada produto como o endpoint de um item, com o mesmo
     * motivo para todos, e responde 200 com o desfecho de cada um na ordem do
     * pedido. Itens inválidos ou repetidos (400) e motivo inválido (422)
     * recusam o pedido inteiro, sem gravar nada.
     */
    @POST
    @Path("/products/batch")
    @Operation(operationId = "batchModerateProducts", summary = "Aprova, rejeita ou bloqueia até 100 produtos, cada um isolado")
    @APIResponse(responseCode = "200", description = "Desfecho de cada produto, na ordem do pedido")
    public Uni<ProductBatchResponse> batch(@NotNull @Valid ModerationBatchRequest request, UriInfo uri) {
        Counter counter = byDecision.get(request.action());
        return moderation.batch(caller.actor(), request.action(), request.codes(), request.noteText(),
                BatchItemRequest.toItems(request.items()))
                .invoke(outcomes -> counter.increment(succeeded(outcomes)))
                .flatMap(outcomes -> responses.batch(outcomes, uri.getPath()));
    }

    private static long succeeded(List<Batch.Outcome<Product>> outcomes) {
        return outcomes.stream().filter(o -> o.value().isPresent()).count();
    }
}
