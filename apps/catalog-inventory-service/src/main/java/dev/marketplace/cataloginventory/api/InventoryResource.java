package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.api.InventoryModels.AdjustStockRequest;
import dev.marketplace.cataloginventory.api.InventoryModels.StockResponse;
import dev.marketplace.cataloginventory.application.InventoryService;
import dev.marketplace.cataloginventory.observability.InventoryLatency;
import dev.marketplace.cataloginventory.security.CallerContext;
import dev.marketplace.cataloginventory.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
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
import org.jboss.resteasy.reactive.RestResponse;
import java.util.UUID;

/** Saldo por SKU: físico vendável, reservado e disponível. */
@Path("/inventory/skus/{skuId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Inventory", description = "Saldo por SKU e reservas de estoque")
@APIResponse(responseCode = "4XX", description = "Erro do cliente (RFC 9457)", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@APIResponse(responseCode = "5XX", description = "Erro do servidor (RFC 9457)", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@Traced(entry = true)
public class InventoryResource {
    private final InventoryService inventory;
    private final CallerContext caller;
    private final InventoryLatency latency;

    InventoryResource(InventoryService inventory, CallerContext caller, InventoryLatency latency) {
        this.inventory = inventory;
        this.caller = caller;
        this.latency = latency;
    }

    /**
     * Devolve o saldo com o ETag da versão, usado no {@code If-Match} do ajuste;
     * 404 se o SKU não
     * existe ou não é visível ao chamador.
     */
    @GET
    @RolesAllowed({ Roles.SELLER, Roles.CATALOG_ADMIN, Roles.CATALOG_MODERATOR })
    @Operation(operationId = "getStock", summary = "Saldo do SKU; ETag carrega a versão do saldo")
    @APIResponse(responseCode = "200", description = "Saldo")
    public Uni<RestResponse<StockResponse>> get(@RestPath UUID skuId) {
        return inventory.get(caller.actor(), skuId).map(level -> ETags.ok(StockResponse.of(level), level.version()));
    }

    /**
     * Define o estoque físico absoluto. Com {@code If-Match}, responde 412 se a
     * versão divergiu, o
     * que evita sobrescrever uma contagem concorrente. Responde 409
     * {@code stock-below-reserved} se
     * o valor ficar abaixo do reservado.
     */
    @PUT
    @RolesAllowed(Roles.SELLER)
    @Operation(operationId = "adjustStock", summary = "Define o estoque físico; nunca abaixo do reservado (If-Match opcional)")
    @APIResponse(responseCode = "200", description = "Saldo atualizado")
    public Uni<RestResponse<StockResponse>> adjust(@RestPath UUID skuId, @RestHeader("If-Match") String ifMatch,
            @NotNull @Valid AdjustStockRequest request) {
        return latency.timed("adjust_stock",
                inventory.adjust(caller.actor(), skuId, ETags.optional(ifMatch), request.onHand()))
                .map(level -> ETags.ok(StockResponse.of(level), level.version()));
    }
}
