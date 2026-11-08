package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.api.ReservationModels.*;
import dev.marketplace.cataloginventory.application.ReservationService;
import dev.marketplace.cataloginventory.domain.inventory.ReservationRefusal;
import dev.marketplace.cataloginventory.observability.InventoryLatency;
import dev.marketplace.cataloginventory.security.CallerContext;
import dev.marketplace.cataloginventory.security.Roles;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestHeader;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Reservas de estoque (PRD): o checkout reserva, o OMS confirma ou libera;
 * prazo vencido devolve o estoque.
 */
@Path("/inventory/reservations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.CATALOG_RESERVATIONS)
@Tag(name = "Inventory", description = "Saldo por SKU e reservas de estoque")
@APIResponse(responseCode = "4XX", description = "Erro do cliente (RFC 9457)", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@APIResponse(responseCode = "5XX", description = "Erro do servidor (RFC 9457)", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@Traced(entry = true)
public class ReservationResource {
    private final ReservationService reservations;
    private final CallerContext caller;
    private final MeterRegistry registry;
    private final InventoryLatency latency;

    ReservationResource(ReservationService reservations, CallerContext caller, MeterRegistry registry,
            InventoryLatency latency) {
        this.reservations = reservations;
        this.caller = caller;
        this.registry = registry;
        this.latency = latency;
    }

    /**
     * Reserva todos os itens ou nenhum, idempotente por {@code Idempotency-Key}:
     * 201 com {@code
     * Location} na primeira chamada e 200 com {@code Idempotent-Replayed: true} na
     * repetição
     * idêntica. Recusa de negócio responde 409 com {@code reasons}; chave
     * reutilizada com outro
     * pedido, 422 {@code idempotency-key-reused}; chave ausente ou inválida, 400.
     */
    @POST
    @Operation(operationId = "createReservation", summary = "Reserva todos os itens ou nenhum; repetir a chave devolve a mesma reserva")
    @APIResponse(responseCode = "201", description = "Reserva criada")
    @APIResponse(responseCode = "200", description = "Repetição idempotente (cabeçalho Idempotent-Replayed)")
    public Uni<RestResponse<ReservationResponse>> create(
            @Parameter(description = "Chave única por tentativa de reserva", required = true) @RestHeader("Idempotency-Key") String key,
            @NotNull @Valid CreateReservationRequest request, UriInfo uri) {
        var command = new ReservationService.Request(key, request.orderRef(),
                Optional.ofNullable(request.ttlSeconds()).map(Duration::ofSeconds),
                request.items().stream().map(ItemRequest::toDomain).toList());
        return latency.timed("reserve", reservations.reserve(caller.actor(), command))
                .onFailure(ReservationRefusal.class).invoke(e -> registry.counter("catalog.reservations.refused",
                        "reason", ((ReservationRefusal) e).code()).increment())
                .map(result -> {
                    ReservationResponse body = ReservationResponse.of(result.reservation());
                    if (result.replayed())
                        return RestResponse.ResponseBuilder.ok(body).header("Idempotent-Replayed", "true").build();
                    registry.counter("catalog.reservations.created").increment();
                    return ETags.created(uri, result.reservation().id(), body);
                });
    }

    /** Devolve a reserva; 404 se não existe no tenant. */
    @GET
    @Path("/{id}")
    @Operation(operationId = "getReservation", summary = "Consulta reserva")
    @APIResponse(responseCode = "200", description = "Reserva")
    public Uni<ReservationResponse> get(@RestPath UUID id) {
        return reservations.get(caller.actor(), id).map(ReservationResponse::of);
    }

    /**
     * Devolve o estoque reservado. Repetir, ou liberar reserva já expirada,
     * responde 200 sem
     * devolver o estoque outra vez; reserva já confirmada responde 409.
     */
    @POST
    @Path("/{id}/release")
    @Operation(operationId = "releaseReservation", summary = "Devolve o estoque (pagamento falhou, carrinho expirou); idempotente")
    @APIResponse(responseCode = "200", description = "Reserva liberada ou já encerrada sem baixa")
    public Uni<ReservationResponse> release(@RestPath UUID id) {
        return latency.timed("release", reservations.release(caller.actor(), id)).map(ReservationResponse::of);
    }

    /**
     * Dá a baixa definitiva do estoque; repetir sobre reserva já confirmada
     * responde 200. Responde
     * 409 {@code reservation-expired} se o prazo venceu e
     * {@code reservation-not-active} se a
     * reserva já foi liberada ou expirada.
     */
    @POST
    @Path("/{id}/commit")
    @Operation(operationId = "commitReservation", summary = "Baixa definitiva após pagamento confirmado; idempotente")
    @APIResponse(responseCode = "200", description = "Reserva confirmada")
    public Uni<ReservationResponse> commit(@RestPath UUID id) {
        return latency.timed("commit", reservations.commit(caller.actor(), id))
                .invoke(r -> registry.counter("catalog.reservations.committed").increment())
                .map(ReservationResponse::of);
    }

    /**
     * Estende o prazo a partir de agora por {@code ttlSeconds}. Responde 422 se o
     * prazo é inválido
     * ou passa do máximo, e 409 ({@code reservation-expired},
     * {@code reservation-not-active} ou
     * {@code reservation-lifetime-exceeded}) se a reserva não pode mais ser
     * estendida.
     */
    @POST
    @Path("/{id}/extend")
    @Operation(operationId = "extendReservation", summary = "Estende o prazo de reserva ativa (ex.: antes de captura do cartão)")
    @APIResponse(responseCode = "200", description = "Reserva com novo prazo")
    public Uni<ReservationResponse> extend(@RestPath UUID id, @NotNull @Valid ExtendReservationRequest request) {
        return latency.timed("extend",
                reservations.extend(caller.actor(), id, Duration.ofSeconds(request.ttlSeconds())))
                .map(ReservationResponse::of);
    }
}
