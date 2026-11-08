package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.application.ProductService.NewSku;
import dev.marketplace.cataloginventory.domain.Money;
import dev.marketplace.cataloginventory.domain.inventory.StockLevel;
import dev.marketplace.cataloginventory.domain.product.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contratos HTTP de produtos: conteúdo, SKUs e publicação. Preço fica em
 * ofertas; saldo, em estoque.
 */
public final class ProductModels {
    private ProductModels() {
    }

    public record DimensionsDto(@NotNull @Positive BigDecimal heightCm, @NotNull @Positive BigDecimal widthCm,
            @NotNull @Positive BigDecimal lengthCm, @NotNull @Positive Integer weightGrams) {
        Dimensions toDomain() {
            return new Dimensions(heightCm, widthCm, lengthCm, weightGrams);
        }

        static DimensionsDto of(Dimensions d) {
            return d == null ? null : new DimensionsDto(d.heightCm(), d.widthCm(), d.lengthCm(), d.weightGrams());
        }

        /** Converte as dimensões recebidas; nulo (SKU sem dimensões) continua nulo. */
        static Dimensions domainOf(DimensionsDto dto) {
            return dto == null ? null : dto.toDomain();
        }
    }

    public record SkuRequest(@NotBlank @Pattern(regexp = Sku.SELLER_SKU_PATTERN) String sellerSku,
            Map<@NotBlank String, @NotNull String> variantAttributes, boolean physical,
            @Schema(description = "Obrigatório para SKU físico", nullable = true) @Valid DimensionsDto dimensions) {
        NewSku toCommand() {
            return new NewSku(sellerSku, variantAttributes, physical, DimensionsDto.domainOf(dimensions));
        }
    }

    public record CreateProductRequest(@NotNull UUID categoryId,
            @Schema(description = "Moeda ISO 4217 de todas as ofertas do produto") @NotBlank @Pattern(regexp = Money.CURRENCY_PATTERN) String currency,
            @NotBlank @Size(max = Product.TITLE_MAX_LENGTH) String title,
            @Size(max = Product.DESCRIPTION_MAX_LENGTH) String description,
            Map<@NotBlank String, @NotNull String> attributes,
            @Size(max = Product.MAX_SKUS) List<@Valid @NotNull SkuRequest> skus) {
    }

    public record ReviseProductRequest(@NotNull UUID categoryId,
            @NotBlank @Size(max = Product.TITLE_MAX_LENGTH) String title,
            @Size(max = Product.DESCRIPTION_MAX_LENGTH) String description,
            Map<@NotBlank String, @NotNull String> attributes) {
    }

    public record ReviseSkuRequest(Map<@NotBlank String, @NotNull String> variantAttributes, boolean physical,
            @Valid DimensionsDto dimensions) {
    }

    public record ReasonRequest(@NotBlank @Size(max = Product.REASON_MAX_LENGTH) String reason) {
    }

    public record SkuResponse(UUID id, String sellerSku, Map<String, String> variantAttributes, boolean physical,
            DimensionsDto dimensions) {
        static SkuResponse of(Sku s) {
            return new SkuResponse(s.id(), s.sellerSku(), s.variantAttributes(), s.physical(),
                    DimensionsDto.of(s.dimensions().orElse(null)));
        }
    }

    public record ProductResponse(UUID id, UUID storeId, UUID categoryId, String currency, String title,
            String description,
            Map<String, String> attributes,
            @Schema(description = "ACTIVE sem saldo disponível aparece como OUT_OF_STOCK") ProductStatus status,
            long version, Instant createdAt, Instant updatedAt, List<SkuResponse> skus) {
        static ProductResponse of(Product p, Map<UUID, StockLevel> stock) {
            return new ProductResponse(p.id(), p.storeId(), p.categoryId(), p.currency(), p.title(), p.description(),
                    p.attributes(),
                    p.effectiveStatus(anyAvailable(p, stock)), p.version(), p.createdAt(), p.updatedAt(),
                    p.skus().stream().map(SkuResponse::of).toList());
        }
    }

    public record ProductPage(List<ProductResponse> items,
            @Schema(description = "Cursor opaco da próxima página; ausente na última", nullable = true) String nextCursor) {
    }

    public record StatusChangeResponse(ProductTransition transition, ProductStatus from, ProductStatus to, String actor,
            String reason,
            Instant at) {
        static StatusChangeResponse of(StatusChange c) {
            return new StatusChangeResponse(c.transition(), c.from(), c.to(), c.actor(), c.reason(), c.at());
        }
    }

    /**
     * Indica se algum SKU tem saldo disponível; o mapa precisa conter o saldo de
     * todo SKU do
     * produto.
     */
    static boolean anyAvailable(Product p, Map<UUID, StockLevel> stock) {
        return p.skus().stream().anyMatch(s -> stock.get(s.id()).available() > 0);
    }
}
