package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.application.SellerEligibilityService;
import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import dev.marketplace.cataloginventory.security.CallerContext;
import dev.marketplace.cataloginventory.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestPath;
import java.util.UUID;

/**
 * Situação KYB/KYC da loja vista pelo catálogo; só muda pelos vereditos do
 * contexto KYB/KYC (ADR 013).
 */
@Path("/stores/{storeId}/eligibility")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Stores", description = "Situação KYB/KYC da loja, recebida do contexto KYB/KYC")
@APIResponse(responseCode = "4XX", description = "Erro do cliente (RFC 9457)", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@APIResponse(responseCode = "5XX", description = "Erro do servidor (RFC 9457)", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@Traced(entry = true)
public class StoreEligibilityResource {
    public record EligibilityResponse(UUID storeId, SellerStatus status) {
    }

    private final SellerEligibilityService eligibility;
    private final CallerContext caller;

    StoreEligibilityResource(SellerEligibilityService eligibility, CallerContext caller) {
        this.eligibility = eligibility;
        this.caller = caller;
    }

    /**
     * Devolve a situação KYB/KYC da loja; o seller consulta só a própria (403 para
     * outra). Loja sem
     * veredito registrado responde {@code PENDING}.
     */
    @GET
    @RolesAllowed({ Roles.SELLER, Roles.CATALOG_ADMIN })
    @Operation(operationId = "getStoreEligibility", summary = "Consulta a situação da loja (seller: só a própria)")
    @APIResponse(responseCode = "200", description = "Situação atual")
    public Uni<EligibilityResponse> get(@RestPath UUID storeId) {
        return eligibility.get(caller.actor(), storeId).map(status -> new EligibilityResponse(storeId, status));
    }
}
