package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.api.OfferModels.*;
import dev.marketplace.cataloginventory.application.OfferService;
import dev.marketplace.cataloginventory.domain.offer.Offer;
import dev.marketplace.cataloginventory.observability.InventoryLatency;
import dev.marketplace.cataloginventory.security.CallerContext;
import io.smallrye.mutiny.Uni;
import io.quarkus.security.PermissionsAllowed;
import jakarta.validation.Valid;
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
import org.jboss.resteasy.reactive.RestResponse;
import java.time.Clock;
import java.util.UUID;

import static dev.marketplace.cataloginventory.security.Capabilities.MANAGE_OWN_PRODUCTS;
import static dev.marketplace.cataloginventory.security.Capabilities.MODERATE_PRODUCTS;
import static dev.marketplace.cataloginventory.security.Capabilities.READ_TENANT_CATALOG;

/**
 * Oferta de cada SKU: preço de/por e promoções, com versão própria. Mudar preço
 * não volta o produto para moderação.
 */
@Path("/offers/{skuId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Offers", description = "Preço de/por e promoções de cada SKU da loja autenticada")
@APIResponse(responseCode = "4XX", description = "Erro do cliente (RFC 9457)", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@APIResponse(responseCode = "5XX", description = "Erro do servidor (RFC 9457)", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@Traced(entry = true)
public class OfferResource {
        private final OfferService offers;
        private final CallerContext caller;
        private final Clock clock;
        private final InventoryLatency latency;

        OfferResource(OfferService offers, CallerContext caller, Clock clock, InventoryLatency latency) {
                this.offers = offers;
                this.caller = caller;
                this.clock = clock;
                this.latency = latency;
        }

        /**
         * Devolve a oferta com o preço vigente no instante da consulta e o ETag da
         * versão; 404 se o SKU
         * não tem oferta.
         */
        @GET
        @PermissionsAllowed({ MANAGE_OWN_PRODUCTS, READ_TENANT_CATALOG, MODERATE_PRODUCTS })
        @Operation(operationId = "getOffer", summary = "Consulta a oferta do SKU com o preço vigente; ETag carrega a versão")
        @APIResponse(responseCode = "200", description = "Oferta")
        public Uni<RestResponse<OfferResponse>> get(@RestPath UUID skuId) {
                return offers.get(caller.actor(), skuId).map(this::ok);
        }

        /**
         * Cria a oferta (201 com {@code Location}) ou troca o preço (200). A troca sem
         * {@code If-Match}
         * responde 428; versão divergente responde 412, inclusive {@code If-Match} numa
         * oferta que
         * ainda não existe. Produto arquivado congela a oferta (409).
         */
        @PUT
        @PermissionsAllowed(MANAGE_OWN_PRODUCTS)
        @Operation(operationId = "defineOffer", summary = "Cria a oferta do SKU na moeda do produto, ou troca o preço da existente (troca exige If-Match)")
        @APIResponse(responseCode = "200", description = "Preço trocado")
        @APIResponse(responseCode = "201", description = "Oferta criada", content = @Content(mediaType = "application/json", schema = @Schema(implementation = OfferResponse.class)))
        public Uni<RestResponse<OfferResponse>> define(@RestPath UUID skuId, @RestHeader("If-Match") String ifMatch,
                        @NotNull @Valid PriceDto request, UriInfo uri) {
                return latency.timed("define_offer",
                                offers.define(caller.actor(), skuId, ETags.optional(ifMatch), request.toDomain()))
                                .map(defined -> defined.created()
                                                ? ETags.created(uri.getAbsolutePath(),
                                                                OfferResponse.of(defined.offer(), clock.instant()),
                                                                defined.offer().version())
                                                : ok(defined.offer()));
        }

        /**
         * Agenda a promoção e responde 201 com {@code Location} dela e o ETag da nova
         * versão da oferta;
         * {@code If-Match} opcional (412 se divergir). Responde 422
         * {@code promotion-overlap} se
         * sobrepõe outra já agendada, além de outras violações de regra.
         */
        @POST
        @Path("/promotions")
        @PermissionsAllowed(MANAGE_OWN_PRODUCTS)
        @Operation(operationId = "schedulePromotion", summary = "Agenda preço promocional [startsAt, endsAt)")
        @APIResponse(responseCode = "201", description = "Promoção agendada", content = @Content(mediaType = "application/json", schema = @Schema(implementation = OfferResponse.class)))
        public Uni<RestResponse<OfferResponse>> schedulePromotion(@RestPath UUID skuId,
                        @RestHeader("If-Match") String ifMatch,
                        @NotNull @Valid PromotionRequest request, UriInfo uri) {
                UUID promotionId = UUID.randomUUID();
                return latency.timed("schedule_promotion", offers
                                .schedulePromotion(caller.actor(), skuId, ETags.optional(ifMatch), promotionId,
                                                request.salePrice().toDomain(), request.startsAt(), request.endsAt()))
                                .map(offer -> ETags.created(uri, promotionId,
                                                OfferResponse.of(offer, clock.instant()), offer.version()));
        }

        /**
         * Cancela a promoção ainda não encerrada; {@code If-Match} opcional (412 se
         * divergir). Responde
         * 422 ({@code promotion-not-found} ou {@code promotion-ended}) se a promoção
         * não existe na
         * oferta ou já encerrou.
         */
        @DELETE
        @Path("/promotions/{promotionId}")
        @PermissionsAllowed(MANAGE_OWN_PRODUCTS)
        @Operation(operationId = "cancelPromotion", summary = "Cancela promoção ainda não encerrada")
        @APIResponse(responseCode = "200", description = "Oferta atualizada")
        public Uni<RestResponse<OfferResponse>> cancelPromotion(@RestPath UUID skuId, @RestPath UUID promotionId,
                        @RestHeader("If-Match") String ifMatch) {
                return latency.timed("cancel_promotion",
                                offers.cancelPromotion(caller.actor(), skuId, promotionId, ETags.optional(ifMatch)))
                                .map(this::ok);
        }

        /** Responde 200 com a oferta, o preço vigente agora e o ETag da versão. */
        private RestResponse<OfferResponse> ok(Offer offer) {
                return ETags.ok(OfferResponse.of(offer, clock.instant()), offer.version());
        }
}
