package dev.marketplace.cataloginventory.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.marketplace.cataloginventory.api.BatchModels.BatchItemRequest;
import dev.marketplace.cataloginventory.api.BatchModels.BatchOutcome;
import dev.marketplace.cataloginventory.application.Batch;
import dev.marketplace.cataloginventory.application.CategoryBatchAction;
import dev.marketplace.cataloginventory.application.CategoryNode;
import dev.marketplace.cataloginventory.application.CategoryService.CategoryView;
import dev.marketplace.cataloginventory.domain.category.AttributeDefinition;
import dev.marketplace.cataloginventory.domain.category.AttributeType;
import dev.marketplace.cataloginventory.domain.category.Category;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Contratos HTTP de categorias; o domínio valida as regras, aqui só a forma.
 */
public final class CategoryModels {
    private CategoryModels() {
    }

    public record AttributeDto(@NotBlank String code, @NotBlank String label, @NotNull AttributeType type,
            boolean required,
            @Schema(description = "Valor pertence a cada SKU (ex.: cor, tamanho)") boolean variant,
            @Size(max = AttributeDefinition.MAX_OPTIONS) List<@NotBlank String> options) {
        AttributeDefinition toDomain() {
            return new AttributeDefinition(code, label, type, required, variant, options);
        }

        static AttributeDto of(AttributeDefinition a) {
            return new AttributeDto(a.code(), a.label(), a.type(), a.required(), a.variant(), a.options());
        }
    }

    public record CreateCategoryRequest(
            @Schema(description = "Categoria pai; ausente para categoria raiz", nullable = true) UUID parentId,
            @NotBlank @Size(max = 60) String code,
            @NotBlank @Size(max = Category.NAME_MAX_LENGTH) String name,
            @Size(max = Category.MAX_ATTRIBUTES) List<@Valid @NotNull AttributeDto> attributes) {
    }

    public record ReviseCategoryRequest(
            @NotBlank @Size(max = Category.NAME_MAX_LENGTH) String name,
            @Size(max = Category.MAX_ATTRIBUTES) List<@Valid @NotNull AttributeDto> attributes,
            @NotNull Boolean active) {
    }

    /**
     * {@code parentId} precisa vir no corpo, mesmo nulo, para que um campo com
     * nome errado não torne a categoria raiz.
     */
    public record MoveCategoryRequest(@JsonProperty(required = true) @Schema(required = true, nullable = true,
            description = "Novo pai; nulo torna a categoria raiz") UUID parentId) {
    }

    @Schema(description = "Categoria no caminho, da raiz até o pai")
    public record CategoryAncestor(UUID id, String code, String name) {
        static CategoryAncestor of(Category c) {
            return new CategoryAncestor(c.id(), c.code(), c.name());
        }

        static CategoryAncestor of(CategoryNode.Ancestor a) {
            return new CategoryAncestor(a.id(), a.code(), a.name());
        }
    }

    @Schema(description = "Atributo efetivo, com a categoria que o define: a própria ou um ancestral")
    public record EffectiveAttributeDto(String code, String label, AttributeType type, boolean required,
            @Schema(description = "Valor pertence a cada SKU (ex.: cor, tamanho)") boolean variant,
            List<String> options,
            @Schema(description = "Categoria que define o atributo") UUID categoryId) {
        static EffectiveAttributeDto of(AttributeDefinition a, UUID categoryId) {
            return new EffectiveAttributeDto(a.code(), a.label(), a.type(), a.required(), a.variant(), a.options(),
                    categoryId);
        }
    }

    public record CategoryResponse(UUID id, UUID parentId, String code, String name, boolean active,
            @Schema(description = "Falso quando a categoria ou um ancestral está inativo") boolean usable,
            long version, List<AttributeDto> attributes,
            @Schema(description = "Atributos herdados dos ancestrais, da raiz para baixo, e depois os próprios") List<EffectiveAttributeDto> effectiveAttributes,
            @Schema(description = "Caminho da raiz até o pai; vazio na raiz") List<CategoryAncestor> ancestors,
            @Schema(description = "Nível na árvore: 1 na raiz") int level,
            @Schema(description = "Subcategorias diretas; só a categoria sem nenhuma pode ser excluída") int childCount) {
        static CategoryResponse of(CategoryView view) {
            Category c = view.category();
            return new CategoryResponse(c.id(), c.parentId().orElse(null), c.code(), c.name(), c.active(),
                    view.usable(), c.version(),
                    c.attributes().stream().map(AttributeDto::of).toList(),
                    view.chain().stream().flatMap(owner -> owner.attributes().stream()
                            .map(a -> EffectiveAttributeDto.of(a, owner.id()))).toList(),
                    view.ancestors().stream().map(CategoryAncestor::of).toList(), view.level(), view.childCount());
        }
    }

    public record CategorySummary(UUID id, UUID parentId, String code, String name, boolean active,
            @Schema(description = "Falso quando a categoria ou um ancestral está inativo") boolean usable,
            long version,
            @Schema(description = "Caminho da raiz até o pai; vazio na raiz") List<CategoryAncestor> ancestors,
            @Schema(description = "Nível na árvore: 1 na raiz") int level,
            @Schema(description = "Subcategorias diretas") int childCount,
            @Schema(description = "Atributos próprios, sem os herdados") int attributeCount) {
        static CategorySummary of(CategoryNode node) {
            Category c = node.category();
            return new CategorySummary(c.id(), c.parentId().orElse(null), c.code(), c.name(), c.active(),
                    node.usable(), c.version(), node.ancestors().stream().map(CategoryAncestor::of).toList(),
                    node.level(), node.childCount(), c.attributes().size());
        }
    }

    public record CategoryPage(List<CategorySummary> items,
            @Schema(description = "Cursor opaco da próxima página; ausente na última", nullable = true) String nextCursor) {
    }

    @Schema(description = "Ação do administrador em lote, com as regras do endpoint de um item")
    public record CategoryBatchRequest(@NotNull CategoryBatchAction action,
            @NotNull @Size(min = 1, max = Batch.MAX_ITEMS) List<@NotNull @Valid BatchItemRequest> items) {
    }

    @Schema(description = "Desfecho de uma categoria do lote, na posição em que veio no pedido")
    public record CategoryBatchResult(UUID id, BatchOutcome outcome,
            @Schema(description = "Situação nova; ausente na falha e na exclusão", nullable = true) Boolean active,
            @Schema(description = "Versão nova, para o próximo If-Match; ausente na falha e na exclusão", nullable = true) Long version,
            @Schema(description = "Problema que o endpoint de um item responderia; ausente no sucesso", nullable = true) Problem problem) {
        /** Resultado do item; a categoria excluída não tem situação nem versão novas. */
        static CategoryBatchResult of(CategoryBatchAction action, Batch.Outcome<Category> outcome, String path) {
            return outcome.value().map(c -> action == CategoryBatchAction.DELETE
                    ? new CategoryBatchResult(c.id(), BatchOutcome.SUCCEEDED, null, null, null)
                    : new CategoryBatchResult(c.id(), BatchOutcome.SUCCEEDED, c.active(), c.version(), null))
                    .orElseGet(() -> new CategoryBatchResult(outcome.id(), BatchOutcome.FAILED, null, null,
                            ProblemMappers.itemProblem(outcome.failure().orElseThrow(), path)));
        }
    }

    public record CategoryBatchResponse(List<CategoryBatchResult> results) {
    }

    /** Converte os atributos recebidos; lista ausente vira lista vazia. */
    static List<AttributeDefinition> toDomain(List<AttributeDto> attributes) {
        return attributes == null ? List.of() : attributes.stream().map(AttributeDto::toDomain).toList();
    }
}
