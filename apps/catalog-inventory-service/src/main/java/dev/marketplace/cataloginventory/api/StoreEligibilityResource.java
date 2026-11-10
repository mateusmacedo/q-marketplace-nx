package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.application.SellerEligibilityService;
import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import dev.marketplace.cataloginventory.security.CallerContext;
import io.smallrye.mutiny.Uni;
import io.quarkus.security.PermissionsAllowed;
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

import static dev.marketplace.cataloginventory.security.Capabilities.MANAGE_OWN_PRODUCTS;
import static dev.marketplace.cataloginventory.security.Capabilities.READ_SELLER_ELIGIBILITY;

/**
 * Situação KYB/KYC da loja vista pelo catálogo; só muda pelos vereditos do
 * contexto KYB/KYC (ADR 013).
 */
@Path("/stores/{storeId}/eligibility")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Stores", description = "Situação KYB/KYC da loja, recebida do contexto KYB/KYC")
@APIResponse(responseCode = "4XX", description = "Erro do cliente (RFC 9457)",
    content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@APIResponse(responseCode = "5XX", description = "Erro do servidor (RFC 9457)",
    content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@Traced(entry = true)
public class StoreEligibilityResource {
  public record EligibilityResponse(UUID storeId, SellerStatus status,
      @Schema(description = "Produtos da loja podem ser aprovados e reativados") boolean canPublish,
      @Schema(description = "Produtos ativos da loja aparecem na vitrine") boolean visibleInStorefront) {
    static EligibilityResponse of(UUID storeId, SellerStatus status) {
      return new EligibilityResponse(storeId, status, Product.ineligibility(status).isEmpty(),
          status.isEligible());
    }
  }

  private final SellerEligibilityService eligibility;
  private final CallerContext caller;

  StoreEligibilityResource(SellerEligibilityService eligibility, CallerContext caller) {
    this.eligibility = eligibility;
    this.caller = caller;
  }

  /**
   * Devolve a situação KYB/KYC da loja; o seller consulta só a própria (403 para
   * outra), e admin e moderador, qualquer loja do tenant. Loja sem veredito
   * registrado responde {@code PENDING}.
   */
  @GET
  @PermissionsAllowed({MANAGE_OWN_PRODUCTS, READ_SELLER_ELIGIBILITY})
  @Operation(operationId = "getStoreEligibility",
      summary = "Consulta a situação da loja (seller: só a própria; admin e moderador: qualquer uma)")
  @APIResponse(responseCode = "200", description = "Situação atual")
  public Uni<EligibilityResponse> get(@RestPath UUID storeId) {
    return eligibility.get(caller.actor(), storeId).map(status -> EligibilityResponse.of(storeId, status));
  }
}
