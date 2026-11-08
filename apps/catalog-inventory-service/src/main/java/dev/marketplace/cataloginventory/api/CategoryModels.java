package dev.marketplace.cataloginventory.api;

import com.fasterxml.jackson.annotation.JsonProperty;
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

    public record CategoryResponse(UUID id, UUID parentId, String code, String name, boolean active,
            @Schema(description = "Falso quando a categoria ou um ancestral está inativo") boolean usable,
            long version, List<AttributeDto> attributes,
            @Schema(description = "Atributos próprios mais os herdados dos ancestrais") List<AttributeDto> effectiveAttributes) {
        static CategoryResponse of(CategoryView view) {
            Category c = view.category();
            return new CategoryResponse(c.id(), c.parentId().orElse(null), c.code(), c.name(), c.active(),
                    view.usable(), c.version(),
                    c.attributes().stream().map(AttributeDto::of).toList(),
                    view.effectiveAttributes().stream().map(AttributeDto::of).toList());
        }
    }

    public record CategorySummary(UUID id, UUID parentId, String code, String name, boolean active, long version) {
        static CategorySummary of(Category c) {
            return new CategorySummary(c.id(), c.parentId().orElse(null), c.code(), c.name(), c.active(), c.version());
        }
    }

    public record CategoryPage(List<CategorySummary> items,
            @Schema(description = "Cursor opaco da próxima página; ausente na última", nullable = true) String nextCursor) {
    }

    /** Converte os atributos recebidos; lista ausente vira lista vazia. */
    static List<AttributeDefinition> toDomain(List<AttributeDto> attributes) {
        return attributes == null ? List.of() : attributes.stream().map(AttributeDto::toDomain).toList();
    }
}
