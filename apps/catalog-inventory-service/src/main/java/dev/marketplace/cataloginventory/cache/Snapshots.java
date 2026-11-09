package dev.marketplace.cataloginventory.cache;

import dev.marketplace.cataloginventory.application.ModerationReasonEntry;
import dev.marketplace.cataloginventory.application.StorefrontService.StorefrontProduct;
import dev.marketplace.cataloginventory.domain.Money;
import dev.marketplace.cataloginventory.domain.category.AttributeDefinition;
import dev.marketplace.cataloginventory.domain.category.AttributeType;
import dev.marketplace.cataloginventory.domain.category.Category;
import dev.marketplace.cataloginventory.domain.offer.Offer;
import dev.marketplace.cataloginventory.domain.offer.Price;
import dev.marketplace.cataloginventory.domain.offer.Promotion;
import dev.marketplace.cataloginventory.domain.product.Dimensions;
import dev.marketplace.cataloginventory.domain.product.ModerationReasonDefinition;
import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import dev.marketplace.cataloginventory.domain.product.ProductTransition;
import dev.marketplace.cataloginventory.domain.product.Sku;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Retratos serializados no Redis, separados dos objetos de domínio: mudar o domínio não muda o formato guardado sem
 * passar por aqui. Valores monetários e medidas são texto decimal exato. Ao ler, o retrato volta a ser agregado pelos
 * métodos {@code restore}, com as mesmas validações da leitura do banco.
 */
// WHY: o Jackson serializa estes records fora do REST; na imagem nativa, sem registro, os componentes não são achados.
@RegisterForReflection
final class Snapshots {
    private Snapshots() {
    }

    /** Produto visível da vitrine com as ofertas dos SKUs. */
    record Storefront(ProductSnapshot product, List<OfferSnapshot> offers) {
        static Storefront of(StorefrontProduct shown) {
            return new Storefront(ProductSnapshot.of(shown.product()),
                    shown.offers().values().stream().map(OfferSnapshot::of).toList());
        }

        StorefrontProduct restore() {
            return new StorefrontProduct(product.restore(), offers.stream().map(OfferSnapshot::restore)
                    .collect(Collectors.toMap(Offer::skuId, Function.identity())));
        }
    }

    record ProductSnapshot(UUID id, UUID tenantId, UUID storeId, UUID categoryId, String currency, String title,
            String description, Map<String, String> attributes, ProductStatus status, long version, Instant createdAt,
            Instant updatedAt, List<SkuSnapshot> skus) {
        static ProductSnapshot of(Product p) {
            return new ProductSnapshot(p.id(), p.tenantId(), p.storeId(), p.categoryId(), p.currency(), p.title(),
                    p.description(), p.attributes(), p.status(), p.version(), p.createdAt(), p.updatedAt(),
                    p.skus().stream().map(SkuSnapshot::of).toList());
        }

        Product restore() {
            // WHY: só produto visível (ACTIVE) entra no cache, e fora da moderação a entrada na fila é nula.
            return Product.restore(id, tenantId, storeId, categoryId, currency, title, description, attributes,
                    skus.stream().map(SkuSnapshot::restore).toList(), status, version, createdAt, updatedAt, null);
        }
    }

    record SkuSnapshot(UUID id, String sellerSku, Map<String, String> variantAttributes, boolean physical,
            DimensionsSnapshot dimensions) {
        static SkuSnapshot of(Sku s) {
            return new SkuSnapshot(s.id(), s.sellerSku(), s.variantAttributes(), s.physical(),
                    s.dimensions().map(DimensionsSnapshot::of).orElse(null));
        }

        Sku restore() {
            return Sku.create(id, sellerSku, variantAttributes, physical,
                    dimensions == null ? null : dimensions.restore());
        }
    }

    record DimensionsSnapshot(String heightCm, String widthCm, String lengthCm, int weightGrams) {
        static DimensionsSnapshot of(Dimensions d) {
            return new DimensionsSnapshot(d.heightCm().toPlainString(), d.widthCm().toPlainString(),
                    d.lengthCm().toPlainString(), d.weightGrams());
        }

        Dimensions restore() {
            return new Dimensions(new BigDecimal(heightCm), new BigDecimal(widthCm), new BigDecimal(lengthCm),
                    weightGrams);
        }
    }

    /** Oferta com preço base e todas as promoções: o preço vigente é calculado a cada leitura, com o instante dela. */
    record OfferSnapshot(UUID skuId, UUID productId, UUID tenantId, UUID storeId, String currency, String listPrice,
            String salePrice, long priceVersion, List<PromotionSnapshot> promotions, long version, Instant createdAt,
            Instant updatedAt) {
        static OfferSnapshot of(Offer o) {
            return new OfferSnapshot(o.skuId(), o.productId(), o.tenantId(), o.storeId(), o.currency(),
                    o.price().listPrice() == null ? null : amount(o.price().listPrice()), amount(o.price().salePrice()),
                    o.priceVersion(), o.promotions().stream().map(PromotionSnapshot::of).toList(), o.version(),
                    o.createdAt(), o.updatedAt());
        }

        Offer restore() {
            Price price = new Price(listPrice == null ? null : new Money(new BigDecimal(listPrice), currency),
                    new Money(new BigDecimal(salePrice), currency));
            return Offer.restore(skuId, productId, tenantId, storeId, currency, price, priceVersion,
                    promotions.stream().map(p -> p.restore(currency)).toList(), version, createdAt, updatedAt);
        }
    }

    record PromotionSnapshot(UUID id, String salePrice, Instant startsAt, Instant endsAt) {
        static PromotionSnapshot of(Promotion p) {
            return new PromotionSnapshot(p.id(), amount(p.salePrice()), p.startsAt(), p.endsAt());
        }

        Promotion restore(String currency) {
            return new Promotion(id, new Money(new BigDecimal(salePrice), currency), startsAt, endsAt);
        }
    }

    /** Lista de categorias de uma leitura da taxonomia (cadeia até a raiz ou página de subcategorias). */
    record Categories(List<CategorySnapshot> items) {
        static Categories of(List<Category> categories) {
            return new Categories(categories.stream().map(CategorySnapshot::of).toList());
        }

        List<Category> restore() {
            return items.stream().map(CategorySnapshot::restore).toList();
        }
    }

    record CategorySnapshot(UUID id, UUID tenantId, List<UUID> ancestry, String code, String name,
            List<AttributeSnapshot> attributes, boolean active, long version) {
        static CategorySnapshot of(Category c) {
            return new CategorySnapshot(c.id(), c.tenantId(), c.ancestry(), c.code(), c.name(),
                    c.attributes().stream().map(AttributeSnapshot::of).toList(), c.active(), c.version());
        }

        Category restore() {
            return Category.restore(id, tenantId, ancestry, code, name,
                    attributes.stream().map(AttributeSnapshot::restore).toList(), active, version);
        }
    }

    record AttributeSnapshot(String code, String label, AttributeType type, boolean required, boolean variant,
            List<String> options) {
        static AttributeSnapshot of(AttributeDefinition a) {
            return new AttributeSnapshot(a.code(), a.label(), a.type(), a.required(), a.variant(), a.options());
        }

        AttributeDefinition restore() {
            return new AttributeDefinition(code, label, type, required, variant, options);
        }
    }

    /** Catálogo de motivos de moderação de um tenant, ativos e inativos, na ordem de exibição. */
    record ModerationReasons(List<ModerationReasonSnapshot> items) {
        static ModerationReasons of(List<ModerationReasonEntry> reasons) {
            return new ModerationReasons(reasons.stream().map(ModerationReasonSnapshot::of).toList());
        }

        List<ModerationReasonEntry> restore() {
            return items.stream().map(ModerationReasonSnapshot::restore).toList();
        }
    }

    record ModerationReasonSnapshot(UUID tenantId, String code, String label, Set<ProductTransition> decisions,
            boolean active, int position, long version, Instant createdAt, Instant updatedAt) {
        static ModerationReasonSnapshot of(ModerationReasonEntry r) {
            ModerationReasonDefinition d = r.definition();
            return new ModerationReasonSnapshot(r.tenantId(), d.code(), d.label(), d.decisions(), d.active(),
                    r.position(), r.version(), r.createdAt(), r.updatedAt());
        }

        ModerationReasonEntry restore() {
            return new ModerationReasonEntry(tenantId, new ModerationReasonDefinition(code, label, decisions, active),
                    position, version, createdAt, updatedAt);
        }
    }

    private static String amount(Money money) {
        return money.amount().toPlainString();
    }
}
