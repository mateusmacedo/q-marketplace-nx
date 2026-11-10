package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.api.BatchModels.BatchItemRequest;
import dev.marketplace.cataloginventory.api.CategoryModels.*;
import dev.marketplace.cataloginventory.application.CategoryNode;
import dev.marketplace.cataloginventory.application.CategoryService;
import dev.marketplace.cataloginventory.application.InvalidInputException;
import dev.marketplace.cataloginventory.application.Page;
import dev.marketplace.cataloginventory.domain.category.Category;
import dev.marketplace.cataloginventory.security.CallerContext;
import io.smallrye.mutiny.Uni;
import io.quarkus.security.PermissionsAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.Explode;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterStyle;
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
import org.jboss.resteasy.reactive.Separator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static dev.marketplace.cataloginventory.security.Capabilities.MANAGE_OWN_PRODUCTS;
import static dev.marketplace.cataloginventory.security.Capabilities.MANAGE_TAXONOMY;
import static dev.marketplace.cataloginventory.security.Capabilities.MODERATE_PRODUCTS;
import static dev.marketplace.cataloginventory.security.Capabilities.READ_TENANT_CATALOG;

@Path("/categories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Categories", description = "Árvore de categorias e atributos do tenant")
@APIResponse(responseCode = "4XX", description = "Erro do cliente (RFC 9457)",
    content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@APIResponse(responseCode = "5XX", description = "Erro do servidor (RFC 9457)",
    content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@Traced(entry = true)
public class CategoryResource {
  /** Cursor da árvore: os códigos do caminho, até 6 de até 60 caracteres, separados por espaço. */
  private static final int TREE_CURSOR_MAX_LENGTH = Category.MAX_DEPTH * 61;
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
  @PermissionsAllowed(MANAGE_TAXONOMY)
  @Operation(operationId = "createCategory", summary = "Cria categoria raiz ou subcategoria")
  @APIResponse(responseCode = "201", description = "Categoria criada",
      content = @Content(mediaType = "application/json", schema = @Schema(implementation = CategoryResponse.class)))
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
  @PermissionsAllowed({MANAGE_OWN_PRODUCTS, MANAGE_TAXONOMY, READ_TENANT_CATALOG, MODERATE_PRODUCTS})
  @Operation(operationId = "getCategory", summary = "Consulta categoria com atributos efetivos (herdados)")
  @APIResponse(responseCode = "200", description = "Categoria")
  public Uni<RestResponse<CategoryResponse>> get(@RestPath UUID id) {
    return categories.get(caller.actor(), id)
        .map(view -> ETags.ok(CategoryResponse.of(view), view.category().version()));
  }

  /**
   * Pagina em ordem de código com cursor opaco; sem {@code parentId} lista as raízes. Responde 400 para
   * {@code parentId} ou cursor malformado e 404 se o pai não existe. Com {@code ids}, só essas categorias numa
   * página; 400 para id malformado, mais de 100 ou junto de {@code parentId} ou {@code cursor}.
   */
  @GET
  @PermissionsAllowed({MANAGE_OWN_PRODUCTS, MANAGE_TAXONOMY, READ_TENANT_CATALOG, MODERATE_PRODUCTS})
  @Operation(operationId = "listCategories",
      summary = "Lista filhos de uma categoria (ou as raízes) em ordem de código", description = """
          Com `ids`, devolve só essas categorias, em ordem de código e numa página só, sem \
          `nextCursor`; id repetido aparece uma vez, e o que não existe no tenant fica de fora. `ids` \
          não combina com `parentId` nem com `cursor` (400), e `limit` não se aplica a ele.""")
  @APIResponse(responseCode = "200", description = "Página de categorias")
  public Uni<CategoryPage> list(@RestQuery String parentId, @RestQuery String cursor,
      @RestQuery @DefaultValue("" + Page.DEFAULT_SIZE) @Min(1) @Max(Page.MAX_SIZE) int limit,
      @Parameter(description = "Ids das categorias, até 100, separados por vírgula; repetir o parâmetro também vale",
          style = ParameterStyle.FORM, explode = Explode.FALSE,
          schema = @Schema(type = SchemaType.ARRAY, implementation = UUID.class,
              maxItems = CategoryService.MAX_IDS)) @RestQuery @Separator(",") List<String> ids) {
    Optional<UUID> parent = QueryParams.uuid(parentId);
    Optional<String> after = Cursors.decode(cursor);
    if (!ids.isEmpty()) {
      if (parent.isPresent() || after.isPresent())
        throw new InvalidInputException("ids não combina com parentId nem com cursor");
      return categories.byIds(caller.actor(), QueryParams.uuids(ids))
          .map(nodes -> new CategoryPage(nodes.stream().map(CategorySummary::of).toList(),
              null));
    }
    return categories.children(caller.actor(), parent, after, limit).map(CategoryResource::page);
  }

  /**
   * Pagina todas as categorias do tenant na ordem da árvore, para os seletores que mostram o caminho de cada
   * uma, em páginas de até {@link CategoryService#TREE_PAGE_SIZE}; 400 para cursor malformado.
   */
  @GET
  @Path("/tree")
  @PermissionsAllowed({MANAGE_OWN_PRODUCTS, MANAGE_TAXONOMY, READ_TENANT_CATALOG, MODERATE_PRODUCTS})
  @Operation(operationId = "listCategoryTree", summary = "Lista todas as categorias do tenant na ordem da árvore",
      description = """
          O pai vem antes das subcategorias, e os irmãos seguem a ordem de código, como numa árvore \
          aberta; cada item traz o caminho desde a raiz. Com `usable=true`, só as categorias que \
          aceitam produtos novos (ela e os ancestrais ativos). A página tem até 1000 itens, que é \
          também o padrão, para quem lê a árvore inteira fazer poucas leituras.""")
  @APIResponse(responseCode = "200", description = "Página de categorias")
  public Uni<CategoryPage> tree(
      @Parameter(
          description = "Só as categorias ativas com todos os ancestrais ativos") @RestQuery @DefaultValue("false") boolean usable,
      @RestQuery String cursor,
      @RestQuery @DefaultValue(""
          + CategoryService.TREE_PAGE_SIZE) @Min(1) @Max(CategoryService.TREE_PAGE_SIZE) int limit) {
    return categories.tree(caller.actor(), Cursors.decode(cursor, TREE_CURSOR_MAX_LENGTH), usable, limit)
        .map(CategoryResource::page);
  }

  /**
   * Exige {@code If-Match}: 428 se ausente e 412 se a versão divergiu. Responde 409 se a categoria tem
   * subcategorias ou produtos, inclusive arquivados.
   */
  @DELETE
  @Path("/{id}")
  @PermissionsAllowed(MANAGE_TAXONOMY)
  @Operation(operationId = "deleteCategory", summary = "Exclui a categoria vazia (exige If-Match)", description = """
      Vale só para a categoria sem subcategorias e sem nenhum produto, nem arquivado. Recusa com 409 \
      `category-has-children` ou `category-has-products`, com o mesmo código em `reasons`, \
      inclusive quando a subcategoria ou o produto é gravado ao mesmo tempo.""")
  @APIResponse(responseCode = "204", description = "Categoria excluída")
  public Uni<Void> delete(@RestPath UUID id, @RestHeader("If-Match") String ifMatch) {
    return categories.delete(caller.actor(), id, ETags.required(ifMatch));
  }

  /**
   * Ativa, desativa ou exclui até 100 categorias, cada uma isolada, e responde 200 com o resultado de cada uma
   * na ordem do pedido. Itens fora de 1 a 100 ou com id repetido: 400.
   */
  @POST
  @Path("/batch")
  @PermissionsAllowed(MANAGE_TAXONOMY)
  @Operation(operationId = "batchCategories",
      summary = "Ativa, desativa ou exclui até 100 categorias, cada uma isolada", description = """
          Cada item exige `version`, como o If-Match do endpoint de um item; sem ela, o item falha com \
          428. Ativar ou desativar a categoria que já está na situação pedida não grava nada. A exclusão \
          roda das subcategorias para as categorias pai, para que um ramo vazio saia inteiro, e cada \
          item recusa como `DELETE /categories/{id}`.""")
  @APIResponse(responseCode = "200", description = "Resultado de cada categoria, na ordem do pedido")
  public Uni<CategoryBatchResponse> batch(@NotNull @Valid CategoryBatchRequest request, UriInfo uri) {
    return categories.batch(caller.actor(), request.action(), BatchItemRequest.toItems(request.items()))
        .map(outcomes -> new CategoryBatchResponse(outcomes.stream()
            .map(o -> CategoryBatchResult.of(request.action(), o, uri.getPath()))
            .toList()));
  }

  /**
   * Exige {@code If-Match}: 428 se ausente e 412 se a versão divergiu. Responde
   * 422 se os
   * atributos colidem com os herdados ou com os de subcategorias.
   */
  @PUT
  @Path("/{id}")
  @PermissionsAllowed(MANAGE_TAXONOMY)
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
  @PermissionsAllowed(MANAGE_TAXONOMY)
  @Operation(operationId = "moveCategory",
      summary = "Move a categoria e suas subcategorias para outro pai ou para a raiz (exige If-Match)",
      description = """
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

  private static CategoryPage page(Page<CategoryNode> page) {
    return new CategoryPage(page.items().stream().map(CategorySummary::of).toList(), Cursors.next(page));
  }

}
