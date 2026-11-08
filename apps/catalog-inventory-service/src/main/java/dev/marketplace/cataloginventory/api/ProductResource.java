package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.api.ProductModels.*;
import dev.marketplace.cataloginventory.application.InvalidInputException;
import dev.marketplace.cataloginventory.application.Page;
import dev.marketplace.cataloginventory.application.ProductService;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Produtos da loja do seller: conteúdo, SKUs e ciclo de publicação. Preço fica
 * em /offers; saldo, em /inventory.
 */
@Path("/products")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Products", description = "Conteúdo, SKUs e publicação dos produtos da loja autenticada")
@APIResponse(responseCode = "4XX", description = "Erro do cliente (RFC 9457)", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@APIResponse(responseCode = "5XX", description = "Erro do servidor (RFC 9457)", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@Traced(entry = true)
public class ProductResource {
    private final ProductService products;
    private final ProductResponses responses;
    private final CallerContext caller;
    private final Counter created;

    ProductResource(ProductService products, ProductResponses responses, CallerContext caller, MeterRegistry registry) {
        this.products = products;
        this.responses = responses;
        this.caller = caller;
        this.created = Counter.builder("catalog.products.created").description("Produtos criados").register(registry);
    }

    /**
     * Cria o produto em rascunho e responde 201 com {@code Location} e ETag.
     * Responde 409 {@code
     * duplicate-seller-sku} se um SKU do seller já existe na loja e 422 se a
     * categoria está
     * inativa ou o conteúdo viola regra de domínio.
     */
    @POST
    @RolesAllowed(Roles.SELLER)
    @Operation(operationId = "createProduct", summary = "Cria produto em rascunho na loja do token")
    @APIResponse(responseCode = "201", description = "Produto criado")
    public Uni<RestResponse<ProductResponse>> create(@NotNull @Valid CreateProductRequest request, UriInfo uri) {
        List<ProductService.NewSku> skus = request.skus() == null ? List.of()
                : request.skus().stream().map(SkuRequest::toCommand).toList();
        return products
                .create(caller.actor(), request.categoryId(), request.currency(), request.title(),
                        request.description(),
                        request.attributes(), skus)
                .invoke(product -> created.increment())
                .flatMap(product -> responses.of(product)
                        .map(body -> ETags.created(uri, product.id(), body, product.version())));
    }

    /**
     * Pagina por id com cursor opaco. O seller vê só a própria loja e
     * {@code storeId} é ignorado;
     * {@code status}, {@code storeId} ou cursor malformados respondem 400.
     */
    @GET
    @RolesAllowed({ Roles.SELLER, Roles.CATALOG_ADMIN, Roles.CATALOG_MODERATOR })
    @Operation(operationId = "listProducts", summary = "Lista produtos; o seller vê só a própria loja")
    @APIResponse(responseCode = "200", description = "Página de produtos")
    public Uni<ProductPage> list(@RestQuery String status, @RestQuery String storeId, @RestQuery String cursor,
            @RestQuery @DefaultValue("" + Page.DEFAULT_SIZE) @Min(1) @Max(Page.MAX_SIZE) int limit) {
        return products.list(caller.actor(), status(status), QueryParams.uuid(storeId), Cursors.decodeUuid(cursor),
                limit).flatMap(responses::page);
    }

    /**
     * Devolve o produto com o ETag da versão; 404 se não existe ou não é visível ao
     * chamador.
     */
    @GET
    @Path("/{id}")
    @RolesAllowed({ Roles.SELLER, Roles.CATALOG_ADMIN, Roles.CATALOG_MODERATOR })
    @Operation(operationId = "getProduct", summary = "Consulta produto; ETag carrega a versão")
    @APIResponse(responseCode = "200", description = "Produto")
    public Uni<RestResponse<ProductResponse>> get(@RestPath UUID id) {
        return products.get(caller.actor(), id).flatMap(responses::ok);
    }

    /**
     * Exige {@code If-Match}: 428 se ausente e 412 se a versão divergiu. Responde
     * 422 se a
     * categoria está inativa ou o conteúdo viola regra de domínio.
     */
    @PUT
    @Path("/{id}")
    @RolesAllowed(Roles.SELLER)
    @Operation(operationId = "reviseProduct", summary = "Atualiza conteúdo (exige If-Match); produto publicado volta à moderação")
    @APIResponse(responseCode = "200", description = "Produto atualizado")
    public Uni<RestResponse<ProductResponse>> revise(@RestPath UUID id, @RestHeader("If-Match") String ifMatch,
            @NotNull @Valid ReviseProductRequest request) {
        return products.reviseContent(caller.actor(), id, ETags.required(ifMatch), request.categoryId(),
                request.title(), request.description(), request.attributes()).flatMap(responses::ok);
    }

    /**
     * Arquiva o produto; {@code If-Match} opcional (412 se divergir) e 409 se a
     * transição não é
     * permitida no estado atual.
     */
    @DELETE
    @Path("/{id}")
    @RolesAllowed(Roles.SELLER)
    @Operation(operationId = "archiveProduct", summary = "Arquiva o produto (exclusão lógica; histórico preservado)")
    @APIResponse(responseCode = "200", description = "Produto arquivado")
    public Uni<RestResponse<ProductResponse>> archive(@RestPath UUID id, @RestHeader("If-Match") String ifMatch) {
        return products.archive(caller.actor(), id, ETags.optional(ifMatch)).flatMap(responses::ok);
    }

    /**
     * Lista as transições em ordem cronológica; 404 se o produto não é visível ao
     * chamador.
     */
    @GET
    @Path("/{id}/history")
    @RolesAllowed({ Roles.SELLER, Roles.CATALOG_ADMIN, Roles.CATALOG_MODERATOR })
    @Operation(operationId = "productHistory", summary = "Histórico de transições e decisões de moderação")
    @APIResponse(responseCode = "200", description = "Histórico em ordem cronológica")
    public Uni<List<StatusChangeResponse>> history(@RestPath UUID id) {
        return products.history(caller.actor(), id)
                .map(changes -> changes.stream().map(StatusChangeResponse::of).toList());
    }

    /**
     * Envia o rascunho à moderação; {@code If-Match} opcional (412 se divergir).
     * Responde 422, com
     * todos os motivos em {@code reasons}, se o rascunho não atende à política de
     * publicação.
     */
    @POST
    @Path("/{id}/submit")
    @RolesAllowed(Roles.SELLER)
    @Operation(operationId = "submitProduct", summary = "Envia rascunho para moderação; recusa lista todos os motivos")
    @APIResponse(responseCode = "200", description = "Produto em moderação")
    public Uni<RestResponse<ProductResponse>> submit(@RestPath UUID id, @RestHeader("If-Match") String ifMatch) {
        return products.submit(caller.actor(), id, ETags.optional(ifMatch)).flatMap(responses::ok);
    }

    /**
     * Pausa o produto ativo; {@code If-Match} opcional (412 se divergir) e 409 se o
     * produto não
     * está ativo.
     */
    @POST
    @Path("/{id}/pause")
    @RolesAllowed(Roles.SELLER)
    @Operation(operationId = "pauseProduct", summary = "Pausa produto ativo")
    @APIResponse(responseCode = "200", description = "Produto pausado")
    public Uni<RestResponse<ProductResponse>> pause(@RestPath UUID id, @RestHeader("If-Match") String ifMatch) {
        return products.pause(caller.actor(), id, ETags.optional(ifMatch)).flatMap(responses::ok);
    }

    /**
     * Reativa o produto pausado; {@code If-Match} opcional (412 se divergir).
     * Responde 409 se o
     * produto não está pausado e 422 se o seller não está aprovado.
     */
    @POST
    @Path("/{id}/resume")
    @RolesAllowed(Roles.SELLER)
    @Operation(operationId = "resumeProduct", summary = "Reativa produto pausado (exige seller aprovado)")
    @APIResponse(responseCode = "200", description = "Produto ativo")
    public Uni<RestResponse<ProductResponse>> resume(@RestPath UUID id, @RestHeader("If-Match") String ifMatch) {
        return products.resume(caller.actor(), id, ETags.optional(ifMatch)).flatMap(responses::ok);
    }

    /**
     * Adiciona o SKU e responde 201 com {@code Location} dele; {@code If-Match}
     * opcional (412 se
     * divergir) e 409 {@code duplicate-seller-sku} se o SKU do seller já existe na
     * loja.
     */
    @POST
    @Path("/{id}/skus")
    @RolesAllowed(Roles.SELLER)
    @Operation(operationId = "addSku", summary = "Adiciona SKU; produto publicado volta à moderação")
    @APIResponse(responseCode = "201", description = "SKU adicionado")
    public Uni<RestResponse<ProductResponse>> addSku(@RestPath UUID id, @RestHeader("If-Match") String ifMatch,
            @NotNull @Valid SkuRequest request, UriInfo uri) {
        return products.addSku(caller.actor(), id, ETags.optional(ifMatch), request.toCommand())
                .flatMap(product -> {
                    UUID skuId = product.skus().stream().filter(s -> s.sellerSku().equals(request.sellerSku()))
                            .findFirst().orElseThrow().id();
                    return responses.of(product)
                            .map(body -> ETags.created(uri, skuId, body, product.version()));
                });
    }

    /**
     * Devolve a variação do SKU com o ETag da versão do produto, não do SKU; 404 se
     * o produto não existe ou está fora do alcance do chamador e 422
     * {@code sku-not-found} se o SKU não pertence ao produto.
     */
    @GET
    @Path("/{id}/skus/{skuId}")
    @RolesAllowed({ Roles.SELLER, Roles.CATALOG_ADMIN, Roles.CATALOG_MODERATOR })
    @Operation(operationId = "getSku", summary = "Consulta a variação do SKU; ETag carrega a versão do produto")
    @APIResponse(responseCode = "200", description = "SKU")
    public Uni<RestResponse<SkuResponse>> getSku(@RestPath UUID id, @RestPath UUID skuId) {
        return products.get(caller.actor(), id)
                .map(product -> ETags.ok(SkuResponse.of(product.sku(skuId)), product.version()));
    }

    /**
     * Exige {@code If-Match} sobre a versão do produto: 428 se ausente e 412 se
     * divergiu.
     */
    @PUT
    @Path("/{id}/skus/{skuId}")
    @RolesAllowed(Roles.SELLER)
    @Operation(operationId = "reviseSku", summary = "Atualiza variação e dimensões do SKU (exige If-Match)")
    @APIResponse(responseCode = "200", description = "Produto atualizado")
    public Uni<RestResponse<ProductResponse>> reviseSku(@RestPath UUID id, @RestPath UUID skuId,
            @RestHeader("If-Match") String ifMatch,
            @NotNull @Valid ReviseSkuRequest request) {
        return products.reviseSku(caller.actor(), id, skuId, ETags.required(ifMatch), request.variantAttributes(),
                request.physical(), DimensionsDto.domainOf(request.dimensions())).flatMap(responses::ok);
    }

    /**
     * Remove o SKU; {@code If-Match} opcional (412 se divergir). Responde 422
     * {@code sku-removal-requires-draft} fora de {@code DRAFT}, 422
     * {@code sku-not-found} se o SKU não pertence ao produto e 409
     * {@code sku-has-stock} se o SKU ainda tem estoque ou reserva.
     */
    @DELETE
    @Path("/{id}/skus/{skuId}")
    @RolesAllowed(Roles.SELLER)
    @Operation(operationId = "removeSku", summary = "Remove SKU de produto em rascunho, com a oferta dele")
    @APIResponse(responseCode = "200", description = "Produto atualizado")
    public Uni<RestResponse<ProductResponse>> removeSku(@RestPath UUID id, @RestPath UUID skuId,
            @RestHeader("If-Match") String ifMatch) {
        return products.removeSku(caller.actor(), id, skuId, ETags.optional(ifMatch)).flatMap(responses::ok);
    }

    /**
     * Converte o filtro {@code status}; ausente ou em branco significa sem filtro e
     * valor
     * desconhecido responde 400.
     */
    private static Optional<ProductStatus> status(String value) {
        if (value == null || value.isBlank())
            return Optional.empty();
        try {
            return Optional.of(ProductStatus.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("status inválido: " + value);
        }
    }
}
