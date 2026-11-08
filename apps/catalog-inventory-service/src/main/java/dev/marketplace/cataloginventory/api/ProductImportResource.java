package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.observability.Lifecycle;
import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.CatalogConfig;
import dev.marketplace.cataloginventory.api.ProductImportModels.ImportResponse;
import dev.marketplace.cataloginventory.application.Actor;
import dev.marketplace.cataloginventory.application.ProductImportService;
import dev.marketplace.cataloginventory.imports.Offload;
import dev.marketplace.cataloginventory.imports.ProductCsv;
import dev.marketplace.cataloginventory.security.CallerContext;
import dev.marketplace.cataloginventory.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestResponse;
import java.util.List;
import java.util.UUID;

/**
 * Importação em lote dos produtos da loja por planilha CSV: o envio é validado
 * na hora e processado em segundo plano; o resultado sai por linha.
 */
@Path("/products/imports")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Products", description = "Conteúdo, SKUs e publicação dos produtos da loja autenticada")
@APIResponse(responseCode = "4XX", description = "Erro do cliente (RFC 9457)", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@APIResponse(responseCode = "5XX", description = "Erro do servidor (RFC 9457)", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@Traced(entry = true)
public class ProductImportResource {
    static final String CSV = "text/csv";
    private final ProductImportService imports;
    private final CallerContext caller;
    private final CatalogConfig config;
    private final Lifecycle lifecycle;

    ProductImportResource(ProductImportService imports, CallerContext caller, CatalogConfig config,
            Lifecycle lifecycle) {
        this.imports = imports;
        this.caller = caller;
        this.config = config;
        this.lifecycle = lifecycle;
    }

    /**
     * Valida codificação, sintaxe, cabeçalho e quantidade de linhas num worker
     * e registra o lote; responde 202 com {@code Location}. Arquivo recusado:
     * 400 {@code invalid-import-file} com os motivos em {@code reasons}.
     */
    @POST
    @Consumes(CSV)
    @RolesAllowed(Roles.SELLER)
    @Operation(operationId = "importProducts", summary = "Envia planilha CSV de produtos para importação em segundo plano")
    @APIResponse(responseCode = "202", description = "Importação aceita; acompanhe pelo Location")
    public Uni<RestResponse<ImportResponse>> submit(@RequestBody(required = true, description = """
            CSV RFC 4180 em UTF-8 (BOM opcional) com o cabeçalho exato, nesta ordem: \
            product_ref,category_code,title,description,attributes,seller_sku,variant_attributes,physical,\
            height_cm,width_cm,length_cm,weight_g,currency,list_price,sale_price,on_hand. Atributos são pares \
            chave=valor separados por ';'; números usam ponto decimal; physical é true ou false. SKU já existente \
            na loja recebe só preço e estoque; SKUs novos com o mesmo product_ref formam um produto em rascunho.""") byte[] body,
            UriInfo uri) {
        Actor actor = caller.actor();
        int maxRows = config.imports().maxRows();
        return lifecycle.nested(ProductCsv.class, "validate", () -> Offload.cpu(() -> ProductCsv.validate(body, maxRows)))
                .flatMap(file -> imports.submit(actor, file.content(), file.totalLines()))
                .map(job -> RestResponse.ResponseBuilder.<ImportResponse>create(RestResponse.Status.ACCEPTED)
                        .location(uri.getAbsolutePathBuilder().path(job.id().toString()).build())
                        .entity(ImportResponse.of(job, List.of())).build());
    }

    /**
     * Situação do lote, contagens por resultado e as linhas já processadas; só o
     * seller da loja que enviou vê, e outra loja ou tenant recebe 404.
     */
    @GET
    @Path("/{id}")
    @RolesAllowed(Roles.SELLER)
    @Operation(operationId = "getProductImport", summary = "Consulta a importação e o resultado de cada linha")
    @APIResponse(responseCode = "200", description = "Importação")
    public Uni<ImportResponse> get(@RestPath UUID id) {
        return imports.get(caller.actor(), id).map(ImportResponse::of);
    }
}
