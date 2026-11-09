package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.api.ModerationModels.CreateModerationReasonRequest;
import dev.marketplace.cataloginventory.api.ModerationModels.ModerationReasonResponse;
import dev.marketplace.cataloginventory.api.ModerationModels.ReviseModerationReasonRequest;
import dev.marketplace.cataloginventory.application.InvalidInputException;
import dev.marketplace.cataloginventory.application.ModerationDecision;
import dev.marketplace.cataloginventory.application.ModerationReasonEntry;
import dev.marketplace.cataloginventory.application.ModerationReasonService;
import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.security.CallerContext;
import io.smallrye.mutiny.Uni;
import io.quarkus.security.PermissionsAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestHeader;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;
import java.util.List;
import java.util.Optional;

import static dev.marketplace.cataloginventory.security.Capabilities.MANAGE_MODERATION_REASONS;
import static dev.marketplace.cataloginventory.security.Capabilities.MODERATE_PRODUCTS;

/**
 * Catálogo de motivos de rejeição e bloqueio do tenant: o moderador lê os ativos, e o {@code catalog-admin} mantém o
 * catálogo. Sem exclusão, porque o código fica no histórico e nos eventos; desativar tira o motivo das decisões novas.
 */
@Path("/moderation/reasons")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Moderation", description = "Trust & Safety: fila, aprovação, rejeição e bloqueio de produtos")
@APIResponse(responseCode = "4XX", description = "Erro do cliente (RFC 9457)", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@APIResponse(responseCode = "5XX", description = "Erro do servidor (RFC 9457)", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@Traced(entry = true)
public class ModerationReasonResource {
    private final ModerationReasonService reasons;
    private final CallerContext caller;

    ModerationReasonResource(ModerationReasonService reasons, CallerContext caller) {
        this.reasons = reasons;
        this.caller = caller;
    }

    /**
     * Motivos do tenant na ordem de exibição. O moderador recebe os ativos; {@code includeInactive=true} é só para
     * quem administra o catálogo (403 aos demais). {@code decision} malformado responde 400.
     */
    @GET
    @PermissionsAllowed({ MODERATE_PRODUCTS, MANAGE_MODERATION_REASONS })
    @Operation(operationId = "listModerationReasons", summary = "Motivos prontos de rejeição e bloqueio do tenant", description = """
            Lista na ordem de `position` e, no empate, de `code`, que é também a ordem dos rótulos no texto da \
            decisão. Tenant sem motivos responde lista vazia, e o moderador decide só com o complemento.""")
    @APIResponse(responseCode = "200", description = "Catálogo de motivos")
    public Uni<List<ModerationReasonResponse>> list(
            @Parameter(description = "Inclui os inativos; exige MANAGE_MODERATION_REASONS") @RestQuery @DefaultValue("false") boolean includeInactive,
            @Parameter(description = "Só os motivos que valem para a decisão", schema = @Schema(type = SchemaType.STRING, enumeration = {
                    "REJECT", "BLOCK" })) @RestQuery String decision) {
        return reasons.list(caller.actor(), includeInactive, decision(decision))
                .map(found -> found.stream().map(ModerationReasonResponse::of).toList());
    }

    /** Devolve o motivo, ativo ou não, com o ETag da versão; 404 se não existe no tenant. */
    @GET
    @Path("/{code}")
    @PermissionsAllowed(MANAGE_MODERATION_REASONS)
    @Operation(operationId = "getModerationReason", summary = "Consulta motivo de moderação; ETag carrega a versão")
    @APIResponse(responseCode = "200", description = "Motivo")
    public Uni<RestResponse<ModerationReasonResponse>> get(@RestPath String code) {
        return reasons.get(caller.actor(), code).map(ModerationReasonResource::ok);
    }

    /**
     * Cria o motivo ativo e responde 201 com {@code Location} e ETag. Código já usado no tenant: 409
     * {@code duplicate-moderation-reason-code}; decisão que não leva motivo, teto de ativos ou do catálogo: 422.
     */
    @POST
    @PermissionsAllowed(MANAGE_MODERATION_REASONS)
    @Operation(operationId = "createModerationReason", summary = "Cria motivo de moderação no catálogo do tenant", description = """
            O código é imutável e só vale neste tenant. Recusa com 409 `duplicate-moderation-reason-code` o \
            código já usado, inclusive por um motivo inativo. Recusa com 422 `moderation-reason-limit` quando o \
            tenant já tem o máximo de motivos ativos (desativar um abre a vaga) e com 422 \
            `moderation-reason-catalog-limit` quando o catálogo, contando os inativos, chegou ao teto de proteção.""")
    @APIResponse(responseCode = "201", description = "Motivo criado", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ModerationReasonResponse.class)))
    public Uni<RestResponse<ModerationReasonResponse>> create(@NotNull @Valid CreateModerationReasonRequest request,
            UriInfo uri) {
        return reasons.create(caller.actor(), request.code(), request.label(), request.decisions(),
                request.positionValue())
                .map(created -> ETags.created(uri.getAbsolutePathBuilder().path(created.code()).build(),
                        ModerationReasonResponse.of(created), created.version()));
    }

    /**
     * Exige {@code If-Match}: 428 se ausente e 412 se a versão divergiu. O código e a situação não mudam; as decisões
     * passadas guardam o rótulo da época.
     */
    @PUT
    @Path("/{code}")
    @PermissionsAllowed(MANAGE_MODERATION_REASONS)
    @Operation(operationId = "reviseModerationReason", summary = "Atualiza rótulo, decisões e posição do motivo (exige If-Match)")
    @APIResponse(responseCode = "200", description = "Motivo atualizado")
    public Uni<RestResponse<ModerationReasonResponse>> revise(@RestPath String code,
            @RestHeader("If-Match") String ifMatch, @NotNull @Valid ReviseModerationReasonRequest request) {
        return reasons.revise(caller.actor(), code, ETags.required(ifMatch), request.label(), request.decisions(),
                request.position()).map(ModerationReasonResource::ok);
    }

    /**
     * Exige {@code If-Match}: 428 se ausente e 412 se a versão divergiu; já ativo, não grava nada. Com o máximo de
     * motivos ativos: 422 {@code moderation-reason-limit}.
     */
    @POST
    @Path("/{code}/activate")
    @PermissionsAllowed(MANAGE_MODERATION_REASONS)
    @Operation(operationId = "activateModerationReason", summary = "Volta a oferecer o motivo ao moderador (exige If-Match)", description = """
            Já ativo, não grava e devolve o mesmo ETag. Recusa com 422 `moderation-reason-limit` quando o tenant \
            já tem o máximo de motivos ativos; desative outro antes.""")
    @APIResponse(responseCode = "200", description = "Motivo ativo")
    public Uni<RestResponse<ModerationReasonResponse>> activate(@RestPath String code,
            @RestHeader("If-Match") String ifMatch) {
        return reasons.activate(caller.actor(), code, ETags.required(ifMatch)).map(ModerationReasonResource::ok);
    }

    /**
     * Exige {@code If-Match}: 428 se ausente e 412 se a versão divergiu; já inativo, não grava nada. O motivo inativo
     * some da lista do moderador e é recusado nas decisões novas com {@code reason-inactive}.
     */
    @POST
    @Path("/{code}/deactivate")
    @PermissionsAllowed(MANAGE_MODERATION_REASONS)
    @Operation(operationId = "deactivateModerationReason", summary = "Tira o motivo das decisões novas, sem apagar o histórico (exige If-Match)")
    @APIResponse(responseCode = "200", description = "Motivo inativo")
    public Uni<RestResponse<ModerationReasonResponse>> deactivate(@RestPath String code,
            @RestHeader("If-Match") String ifMatch) {
        return reasons.deactivate(caller.actor(), code, ETags.required(ifMatch)).map(ModerationReasonResource::ok);
    }

    private static RestResponse<ModerationReasonResponse> ok(ModerationReasonEntry reason) {
        return ETags.ok(ModerationReasonResponse.of(reason), reason.version());
    }

    private static Optional<ModerationDecision> decision(String value) {
        if (value == null || value.isBlank())
            return Optional.empty();
        try {
            return Optional.of(ModerationDecision.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("Decisão inválida: " + value);
        }
    }
}
