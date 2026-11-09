package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.api.BatchModels.BatchItemRequest;
import dev.marketplace.cataloginventory.api.ProductModels.*;
import dev.marketplace.cataloginventory.application.Actor;
import dev.marketplace.cataloginventory.application.InvalidInputException;
import dev.marketplace.cataloginventory.application.Page;
import dev.marketplace.cataloginventory.application.ProductQuery;
import dev.marketplace.cataloginventory.application.ProductService;
import dev.marketplace.cataloginventory.application.ProductSort;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import dev.marketplace.cataloginventory.security.CallerContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.mutiny.Uni;
import io.quarkus.security.PermissionsAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import java.util.UUID;

import static dev.marketplace.cataloginventory.security.Capabilities.MANAGE_OWN_PRODUCTS;
import static dev.marketplace.cataloginventory.security.Capabilities.MODERATE_PRODUCTS;
import static dev.marketplace.cataloginventory.security.Capabilities.READ_TENANT_CATALOG;

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
    /** Chave do cursor da listagem: ordem, filtros, id e a chave do título, que tem até 400 pontos de código. */
    private static final int CURSOR_KEY_MAX_LENGTH = 1024;
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
    @PermissionsAllowed(MANAGE_OWN_PRODUCTS)
    @Operation(operationId = "createProduct", summary = "Cria produto em rascunho na loja do token")
    @APIResponse(responseCode = "201", description = "Produto criado", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductResponse.class)))
    public Uni<RestResponse<ProductResponse>> create(@NotNull @Valid CreateProductRequest request, UriInfo uri) {
        Actor actor = caller.actor();
        List<ProductService.NewSku> skus = request.skus() == null ? List.of()
                : request.skus().stream().map(SkuRequest::toCommand).toList();
        return products
                .create(actor, request.categoryId(), request.currency(), request.title(),
                        request.description(),
                        request.attributes(), skus)
                .invoke(product -> created.increment())
                .flatMap(product -> responses.saved(actor, product)
                        .map(body -> ETags.created(uri, product.id(), body, product.version())));
    }

    /**
     * Pagina na ordem {@code sort} com cursor opaco, que só vale para a mesma
     * ordem, busca e filtros. O seller vê só a própria loja e {@code storeId} é
     * ignorado; moderador e admin veem todas. {@code status}, {@code sort},
     * {@code storeId}, {@code q} longo ou cursor de outra consulta respondem 400.
     */
    @GET
    @PermissionsAllowed({ MANAGE_OWN_PRODUCTS, READ_TENANT_CATALOG, MODERATE_PRODUCTS })
    @Operation(operationId = "listProducts", summary = "Lista produtos com busca e ordem; o seller vê só a própria loja")
    @APIResponse(responseCode = "200", description = "Página de produtos")
    public Uni<ProductPage> list(
            @Parameter(description = "Estado como a resposta o mostra; OUT_OF_STOCK é o ACTIVE sem saldo disponível, e ACTIVE exige saldo") @RestQuery String status,
            @Parameter(description = "Loja; vale para moderador e admin, e o seller vê sempre a própria") @RestQuery String storeId,
            @Parameter(description = "Trecho do título, sem diferenciar maiúsculas e acentos, ou do código do SKU do seller") @RestQuery @Size(max = ProductQuery.TEXT_MAX_LENGTH) String q,
            @Parameter(description = "Coluna da ordem; o prefixo - inverte", schema = @Schema(type = SchemaType.STRING, enumeration = {
                    "updatedAt", "-updatedAt", "createdAt", "-createdAt", "title",
                    "-title" }, defaultValue = "-updatedAt")) @RestQuery String sort,
            @RestQuery String cursor,
            @RestQuery @DefaultValue("" + Page.DEFAULT_SIZE) @Min(1) @Max(Page.MAX_SIZE) int limit) {
        Actor actor = caller.actor();
        return products.list(actor, status(status), QueryParams.uuid(storeId), Optional.ofNullable(q),
                ProductSort.parse(Optional.ofNullable(sort)), Cursors.decode(cursor, CURSOR_KEY_MAX_LENGTH), limit)
                .flatMap(page -> responses.page(actor, page));
    }

    /**
     * Devolve o produto com o ETag da versão; 404 se não existe ou não é visível ao
     * chamador.
     */
    @GET
    @Path("/{id}")
    @PermissionsAllowed({ MANAGE_OWN_PRODUCTS, READ_TENANT_CATALOG, MODERATE_PRODUCTS })
    @Operation(operationId = "getProduct", summary = "Consulta produto; ETag carrega a versão")
    @APIResponse(responseCode = "200", description = "Produto")
    public Uni<RestResponse<ProductResponse>> get(@RestPath UUID id) {
        Actor actor = caller.actor();
        return products.get(actor, id).flatMap(product -> responses.ok(actor, product));
    }

    /**
     * Exige {@code If-Match}: 428 se ausente e 412 se a versão divergiu. Responde
     * 422 se a
     * categoria está inativa ou o conteúdo viola regra de domínio.
     */
    @PUT
    @Path("/{id}")
    @PermissionsAllowed(MANAGE_OWN_PRODUCTS)
    @Operation(operationId = "reviseProduct", summary = "Atualiza conteúdo (exige If-Match); produto publicado volta à moderação")
    @APIResponse(responseCode = "200", description = "Produto atualizado")
    public Uni<RestResponse<ProductResponse>> revise(@RestPath UUID id, @RestHeader("If-Match") String ifMatch,
            @NotNull @Valid ReviseProductRequest request) {
        Actor actor = caller.actor();
        return products.reviseContent(actor, id, ETags.required(ifMatch), request.categoryId(),
                request.title(), request.description(), request.attributes())
                .flatMap(product -> responses.savedOk(actor, product));
    }

    /**
     * Confere o rascunho do conteúdo sem gravar: pendências de publicação e,
     * se a categoria muda, o efeito da troca. Alcance da edição: 404 fora da
     * loja do seller ou com categoria fora do tenant e 409 em BLOCKED e ARCHIVED.
     */
    @POST
    @Path("/{id}/check")
    @PermissionsAllowed(MANAGE_OWN_PRODUCTS)
    @Operation(operationId = "checkProduct", summary = "Confere o rascunho sem gravar: pendências de publicação e efeito da troca de categoria")
    @APIResponse(responseCode = "200", description = "Pendências e efeito da troca")
    public Uni<ProductCheckResponse> check(@RestPath UUID id, @NotNull @Valid ProductCheckRequest request) {
        return products.check(caller.actor(), id, request.categoryId(), request.attributes())
                .map(ProductCheckResponse::of);
    }

    /**
     * Arquiva o produto; {@code If-Match} opcional (412 se divergir) e 409 se a
     * transição não é
     * permitida no estado atual.
     */
    @DELETE
    @Path("/{id}")
    @PermissionsAllowed(MANAGE_OWN_PRODUCTS)
    @Operation(operationId = "archiveProduct", summary = "Arquiva o produto (exclusão lógica; histórico preservado)")
    @APIResponse(responseCode = "200", description = "Produto arquivado")
    public Uni<RestResponse<ProductResponse>> archive(@RestPath UUID id, @RestHeader("If-Match") String ifMatch) {
        Actor actor = caller.actor();
        return products.archive(actor, id, ETags.optional(ifMatch))
                .flatMap(product -> responses.savedOk(actor, product));
    }

    /**
     * Lista as transições em ordem cronológica, com o lado que fez cada uma e se
     * foi o próprio chamador, sem identificar a pessoa; 404 se o produto não é
     * visível ao chamador.
     */
    @GET
    @Path("/{id}/history")
    @PermissionsAllowed({ MANAGE_OWN_PRODUCTS, READ_TENANT_CATALOG, MODERATE_PRODUCTS })
    @Operation(operationId = "productHistory", summary = "Histórico de transições e decisões de moderação")
    @APIResponse(responseCode = "200", description = "Histórico em ordem cronológica")
    public Uni<List<StatusChangeResponse>> history(@RestPath UUID id) {
        return products.history(caller.actor(), id)
                .map(changes -> changes.stream().map(c -> StatusChangeResponse.of(c, caller.actor().subject())).toList());
    }

    /**
     * Envia o rascunho à moderação; {@code If-Match} opcional (412 se divergir).
     * Responde 422, com
     * todos os motivos em {@code reasons}, se o rascunho não atende à política de
     * publicação.
     */
    @POST
    @Path("/{id}/submit")
    @PermissionsAllowed(MANAGE_OWN_PRODUCTS)
    @Operation(operationId = "submitProduct", summary = "Envia rascunho para moderação; recusa lista todos os motivos")
    @APIResponse(responseCode = "200", description = "Produto em moderação")
    public Uni<RestResponse<ProductResponse>> submit(@RestPath UUID id, @RestHeader("If-Match") String ifMatch) {
        Actor actor = caller.actor();
        return products.submit(actor, id, ETags.optional(ifMatch))
                .flatMap(product -> responses.savedOk(actor, product));
    }

    /**
     * Pausa o produto ativo; {@code If-Match} opcional (412 se divergir) e 409 se o
     * produto não
     * está ativo.
     */
    @POST
    @Path("/{id}/pause")
    @PermissionsAllowed(MANAGE_OWN_PRODUCTS)
    @Operation(operationId = "pauseProduct", summary = "Pausa produto ativo")
    @APIResponse(responseCode = "200", description = "Produto pausado")
    public Uni<RestResponse<ProductResponse>> pause(@RestPath UUID id, @RestHeader("If-Match") String ifMatch) {
        Actor actor = caller.actor();
        return products.pause(actor, id, ETags.optional(ifMatch)).flatMap(product -> responses.savedOk(actor, product));
    }

    /**
     * Reativa o produto pausado; {@code If-Match} opcional (412 se divergir).
     * Responde 409 se o
     * produto não está pausado e 422 se o seller não está aprovado.
     */
    @POST
    @Path("/{id}/resume")
    @PermissionsAllowed(MANAGE_OWN_PRODUCTS)
    @Operation(operationId = "resumeProduct", summary = "Reativa produto pausado (exige seller aprovado)")
    @APIResponse(responseCode = "200", description = "Produto ativo")
    public Uni<RestResponse<ProductResponse>> resume(@RestPath UUID id, @RestHeader("If-Match") String ifMatch) {
        Actor actor = caller.actor();
        return products.resume(actor, id, ETags.optional(ifMatch))
                .flatMap(product -> responses.savedOk(actor, product));
    }

    /**
     * Aplica a ação a cada produto como o endpoint de um item, com
     * {@code version} no lugar do {@code If-Match}, e responde 200 com o
     * desfecho de cada um na ordem do pedido. Itens fora de 1 a 100 ou com id
     * repetido respondem 400, sem gravar nada.
     */
    @POST
    @Path("/batch")
    @PermissionsAllowed(MANAGE_OWN_PRODUCTS)
    @Operation(operationId = "batchProducts", summary = "Envia, pausa, reativa ou arquiva até 100 produtos, cada um isolado")
    @APIResponse(responseCode = "200", description = "Desfecho de cada produto, na ordem do pedido")
    public Uni<ProductBatchResponse> batch(@NotNull @Valid ProductBatchRequest request, UriInfo uri) {
        return products.batch(caller.actor(), request.action(), BatchItemRequest.toItems(request.items()))
                .flatMap(outcomes -> responses.batch(outcomes, uri.getPath()));
    }

    /**
     * Adiciona o SKU e responde 201 com {@code Location} dele; {@code If-Match}
     * opcional (412 se
     * divergir) e 409 {@code duplicate-seller-sku} se o SKU do seller já existe na
     * loja.
     */
    @POST
    @Path("/{id}/skus")
    @PermissionsAllowed(MANAGE_OWN_PRODUCTS)
    @Operation(operationId = "addSku", summary = "Adiciona SKU; produto publicado volta à moderação")
    @APIResponse(responseCode = "201", description = "SKU adicionado", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductResponse.class)))
    public Uni<RestResponse<ProductResponse>> addSku(@RestPath UUID id, @RestHeader("If-Match") String ifMatch,
            @NotNull @Valid SkuRequest request, UriInfo uri) {
        Actor actor = caller.actor();
        return products.addSku(actor, id, ETags.optional(ifMatch), request.toCommand())
                .flatMap(product -> {
                    UUID skuId = product.skus().stream().filter(s -> s.sellerSku().equals(request.sellerSku()))
                            .findFirst().orElseThrow().id();
                    return responses.saved(actor, product)
                            .map(body -> ETags.created(uri, skuId, body, product.version()));
                });
    }

    /**
     * Devolve a variação do SKU, com oferta e saldo, e o ETag da versão do
     * produto, não do SKU; 404 se o produto está fora do alcance do chamador e
     * 422 {@code sku-not-found} se o SKU não pertence ao produto.
     */
    @GET
    @Path("/{id}/skus/{skuId}")
    @PermissionsAllowed({ MANAGE_OWN_PRODUCTS, READ_TENANT_CATALOG, MODERATE_PRODUCTS })
    @Operation(operationId = "getSku", summary = "Consulta a variação do SKU; ETag carrega a versão do produto")
    @APIResponse(responseCode = "200", description = "SKU")
    public Uni<RestResponse<SkuResponse>> getSku(@RestPath UUID id, @RestPath UUID skuId) {
        return products.get(caller.actor(), id).flatMap(product -> responses.sku(product, skuId));
    }

    /**
     * Exige {@code If-Match} sobre a versão do produto: 428 se ausente e 412 se
     * divergiu.
     */
    @PUT
    @Path("/{id}/skus/{skuId}")
    @PermissionsAllowed(MANAGE_OWN_PRODUCTS)
    @Operation(operationId = "reviseSku", summary = "Atualiza variação e dimensões do SKU (exige If-Match)")
    @APIResponse(responseCode = "200", description = "Produto atualizado")
    public Uni<RestResponse<ProductResponse>> reviseSku(@RestPath UUID id, @RestPath UUID skuId,
            @RestHeader("If-Match") String ifMatch,
            @NotNull @Valid ReviseSkuRequest request) {
        Actor actor = caller.actor();
        return products.reviseSku(actor, id, skuId, ETags.required(ifMatch), request.variantAttributes(),
                request.physical(), DimensionsDto.domainOf(request.dimensions()))
                .flatMap(product -> responses.savedOk(actor, product));
    }

    /**
     * Remove o SKU; {@code If-Match} opcional (412 se divergir). Responde 422
     * {@code sku-removal-requires-draft} fora de {@code DRAFT}, 422
     * {@code sku-not-found} se o SKU não pertence ao produto e 409
     * {@code sku-has-stock} se o SKU ainda tem estoque ou reserva.
     */
    @DELETE
    @Path("/{id}/skus/{skuId}")
    @PermissionsAllowed(MANAGE_OWN_PRODUCTS)
    @Operation(operationId = "removeSku", summary = "Remove SKU de produto em rascunho, com a oferta dele")
    @APIResponse(responseCode = "200", description = "Produto atualizado")
    public Uni<RestResponse<ProductResponse>> removeSku(@RestPath UUID id, @RestPath UUID skuId,
            @RestHeader("If-Match") String ifMatch) {
        Actor actor = caller.actor();
        return products.removeSku(actor, id, skuId, ETags.optional(ifMatch))
                .flatMap(product -> responses.savedOk(actor, product));
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
