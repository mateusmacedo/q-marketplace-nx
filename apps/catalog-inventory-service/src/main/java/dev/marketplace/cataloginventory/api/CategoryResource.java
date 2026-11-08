package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.api.CategoryModels.*;
import dev.marketplace.cataloginventory.application.CategoryService;
import dev.marketplace.cataloginventory.application.Page;
import dev.marketplace.cataloginventory.security.CallerContext;
import dev.marketplace.cataloginventory.security.Roles;
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
import java.util.Optional;
import java.util.UUID;

@Path("/categories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Categories", description = "Árvore de categorias e atributos do tenant")
@APIResponse(responseCode = "4XX", description = "Erro do cliente (RFC 9457)", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@APIResponse(responseCode = "5XX", description = "Erro do servidor (RFC 9457)", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@Traced(entry = true)
public class CategoryResource {
        private final CategoryService categories;
        private final CallerContext caller;

        CategoryResource(CategoryService categories, CallerContext caller) {
                this.categories = categories;
                this.caller = caller;
        }

        /**
         * Cria categoria raiz ou subcategoria e responde 201 com {@code Location} e
         * ETag. Falha com
         * 404 se o pai não existe, 409 {@code duplicate-category-code} se o código já
         * existe no
         * tenant e 422 se o pai está inativo ou o conteúdo viola regra de domínio.
         */
        @POST
        @RolesAllowed(Roles.CATALOG_ADMIN)
        @Operation(operationId = "createCategory", summary = "Cria categoria raiz ou subcategoria")
        @APIResponse(responseCode = "201", description = "Categoria criada")
        public Uni<RestResponse<CategoryResponse>> create(@NotNull @Valid CreateCategoryRequest request, UriInfo uri) {
                return categories
                                .create(caller.actor(), Optional.ofNullable(request.parentId()), request.code(),
                                                request.name(),
                                                CategoryModels.toDomain(request.attributes()))
                                .map(view -> ETags.created(uri, view.category().id(), CategoryResponse.of(view),
                                                view.category().version()));
        }

        /** Devolve a categoria com o ETag da versão; 404 se não existe no tenant. */
        @GET
        @Path("/{id}")
        @RolesAllowed({ Roles.SELLER, Roles.CATALOG_ADMIN, Roles.CATALOG_MODERATOR })
        @Operation(operationId = "getCategory", summary = "Consulta categoria com atributos efetivos (herdados)")
        @APIResponse(responseCode = "200", description = "Categoria")
        public Uni<RestResponse<CategoryResponse>> get(@RestPath UUID id) {
                return categories.get(caller.actor(), id)
                                .map(view -> ETags.ok(CategoryResponse.of(view), view.category().version()));
        }

        /**
         * Pagina em ordem de código com cursor opaco; sem {@code parentId} lista as
         * raízes.
         * Responde 400 para {@code parentId} ou cursor malformado e 404 se o pai não
         * existe.
         */
        @GET
        @RolesAllowed({ Roles.SELLER, Roles.CATALOG_ADMIN, Roles.CATALOG_MODERATOR })
        @Operation(operationId = "listCategories", summary = "Lista filhos de uma categoria (ou as raízes) em ordem de código")
        @APIResponse(responseCode = "200", description = "Página de categorias")
        public Uni<CategoryPage> list(@RestQuery String parentId, @RestQuery String cursor,
                        @RestQuery @DefaultValue("" + Page.DEFAULT_SIZE) @Min(1) @Max(Page.MAX_SIZE) int limit) {
                return categories
                                .children(caller.actor(), QueryParams.uuid(parentId), Cursors.decode(cursor), limit)
                                .map(page -> new CategoryPage(page.items().stream().map(CategorySummary::of).toList(),
                                                Cursors.next(page)));
        }

        /**
         * Exige {@code If-Match}: 428 se ausente e 412 se a versão divergiu. Responde
         * 422 se os
         * atributos colidem com os herdados ou com os de subcategorias.
         */
        @PUT
        @Path("/{id}")
        @RolesAllowed(Roles.CATALOG_ADMIN)
        @Operation(operationId = "reviseCategory", summary = "Atualiza nome, atributos próprios e ativação (exige If-Match)")
        @APIResponse(responseCode = "200", description = "Categoria atualizada")
        public Uni<RestResponse<CategoryResponse>> revise(@RestPath UUID id, @RestHeader("If-Match") String ifMatch,
                        @NotNull @Valid ReviseCategoryRequest request) {
                return categories
                                .revise(caller.actor(), id, ETags.required(ifMatch), request.name(),
                                                CategoryModels.toDomain(request.attributes()), request.active())
                                .map(view -> ETags.ok(CategoryResponse.of(view), view.category().version()));
        }

        /**
         * Exige {@code If-Match} da categoria movida: 428 se ausente e 412 se a versão divergiu. Responde 404 se o
         * pai não existe, 422 para ciclo, profundidade ou conflito de atributos e 409 se a árvore mudou durante a
         * gravação.
         */
        @PUT
        @Path("/{id}/parent")
        @RolesAllowed(Roles.CATALOG_ADMIN)
        @Operation(operationId = "moveCategory", summary = "Move a categoria e suas subcategorias para outro pai ou para a raiz (exige If-Match)", description = """
                        Recusa com 422 `category-cycle` (novo pai é a própria categoria ou uma subcategoria dela), \
                        `category-too-deep` (a subárvore passaria do limite de níveis) e `attribute-conflict` \
                        (atributo da subárvore já existe no novo caminho). Pai inativo é aceito. Os produtos não \
                        mudam: atributo obrigatório herdado só é cobrado na próxima edição ou envio de cada produto.""")
        @APIResponse(responseCode = "200", description = "Categoria na nova posição, com os atributos efetivos recalculados")
        public Uni<RestResponse<CategoryResponse>> move(@RestPath UUID id, @RestHeader("If-Match") String ifMatch,
                        @NotNull @Valid MoveCategoryRequest request) {
                return categories
                                .move(caller.actor(), id, ETags.required(ifMatch), Optional.ofNullable(request.parentId()))
                                .map(view -> ETags.ok(CategoryResponse.of(view), view.category().version()));
        }

}
