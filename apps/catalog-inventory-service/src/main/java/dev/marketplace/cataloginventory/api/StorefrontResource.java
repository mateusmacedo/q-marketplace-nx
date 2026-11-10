package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.api.StorefrontModels.StorefrontProductResponse;
import dev.marketplace.cataloginventory.application.InventoryService;
import dev.marketplace.cataloginventory.application.StorefrontService;
import dev.marketplace.cataloginventory.cache.StorefrontCache;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.PermitAll;
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
import org.jboss.resteasy.reactive.RestResponse;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Leitura pública e autoritativa: só produto visível de seller aprovado;
 * bloqueio tira do ar na hora. Produto e ofertas vêm do cache invalidado a cada
 * escrita (ADR 024); o estoque, sempre do banco.
 */
@Path("/storefront/{tenantId}/products/{productId}")
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
@Tag(name = "Storefront",
    description = "Consulta pública e autoritativa de produtos, com preço vigente e disponibilidade")
@APIResponse(responseCode = "4XX", description = "Erro do cliente (RFC 9457)",
    content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@APIResponse(responseCode = "5XX", description = "Erro do servidor (RFC 9457)",
    content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@Traced(entry = true)
public class StorefrontResource {
  private final StorefrontService storefront;
  private final StorefrontCache cache;
  private final InventoryService inventory;
  private final Clock clock;

  StorefrontResource(StorefrontService storefront, StorefrontCache cache, InventoryService inventory, Clock clock) {
    this.storefront = storefront;
    this.cache = cache;
    this.inventory = inventory;
    this.clock = clock;
  }

  /**
   * Devolve o produto da vitrine pública, sem autenticação. Produto inexistente,
   * fora de {@code
   * ACTIVE} e {@code OUT_OF_STOCK}, ou de seller não aprovado responde 404 sem
   * distinguir os
   * casos.
   */
  @GET
  @Operation(operationId = "getStorefrontProduct", summary = "Produto público com o preço vigente de cada SKU")
  @APIResponse(responseCode = "200", description = "Produto visível")
  public Uni<RestResponse<StorefrontProductResponse>> get(@RestPath UUID tenantId, @RestPath UUID productId) {
    // WHY: no-cache obriga revalidação, então caches intermediários não mantêm um
    // produto já bloqueado.
    return cache.get(tenantId, productId, () -> storefront.get(tenantId, productId))
        .flatMap(shown -> inventory.stockOf(List.of(shown.product()))
            .map(stock -> RestResponse.ResponseBuilder
                .ok(StorefrontProductResponse.of(shown, stock, clock.instant()))
                .header("Cache-Control", "no-cache").build()));
  }
}
