package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.application.CategoryService.CategoryView;
import dev.marketplace.cataloginventory.domain.offer.Offer;
import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import io.smallrye.mutiny.Uni;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Contexto de publicação (categoria, seller, ofertas) e gravação otimista,
 * compartilhados pelo seller e pela moderação.
 */
final class ProductSupport {
    /** Categoria do produto e SKUs que já têm oferta: o que a publicação confere. */
    record PublicationContext(CategoryView category, Set<UUID> offeredSkus) {
    }

    final ProductAccess access;
    final CategoryRepository categories;
    final SellerEligibilityRepository sellers;
    final OfferRepository offers;
    final Clock clock;

    ProductSupport(ProductRepository products, CategoryRepository categories, SellerEligibilityRepository sellers,
            OfferRepository offers, Clock clock) {
        this.access = new ProductAccess(products);
        this.categories = categories;
        this.sellers = sellers;
        this.offers = offers;
        this.clock = clock;
    }

    ProductRepository products() {
        return access.products;
    }

    Instant now() {
        return clock.instant();
    }

    /**
     * Carrega a categoria com atributos efetivos e a situação dela e dos
     * ancestrais; inexistente no tenant lança {@link NotFoundException}. A
     * inatividade só falha em {@link CategoryView#requireActive()}.
     */
    Uni<CategoryView> category(UUID tenantId, UUID categoryId) {
        return categories.chain(tenantId, categoryId).map(CategoryView::of);
    }

    /** Como {@link #category}, para a categoria atual do produto. */
    Uni<CategoryView> categoryOf(Product product) {
        return category(product.tenantId(), product.categoryId());
    }

    /**
     * Lê, em sequência, a categoria do produto e os SKUs que já têm oferta; a
     * publicação exige uma oferta para cada SKU.
     */
    Uni<PublicationContext> publicationOf(Product product) {
        return categoryOf(product).flatMap(category -> offers.ofProduct(product.tenantId(), product.id())
                .map(found -> new PublicationContext(category,
                        found.stream().map(Offer::skuId).collect(Collectors.toSet()))));
    }

    /**
     * Consulta a situação KYB/KYC da loja dona do produto; loja sem veredito
     * registrado é {@code PENDING}.
     */
    Uni<SellerStatus> sellerOf(Product product) {
        return sellers.statusOf(product.tenantId(), product.storeId());
    }

    /**
     * Aplica {@code change} a produto da própria loja (exige
     * {@code MANAGE_OWN_PRODUCTS}; alheio é 404) e grava com concorrência
     * otimista; só a conclusão de {@code change} importa. {@code ifMatch}
     * ausente dispensa a checagem; divergente: {@link VersionMismatchException}.
     */
    Uni<Product> mutateOwned(Actor actor, UUID id, Optional<Long> ifMatch, Function<Product, Uni<?>> change) {
        return access.owned(actor, id).flatMap(product -> apply(product, ifMatch, change));
    }

    /**
     * Como {@link #mutateOwned}, mas exige {@code MODERATE_PRODUCTS} e alcança
     * produto de qualquer loja do tenant; inexistente lança
     * {@link NotFoundException}.
     */
    Uni<Product> mutateAsModerator(Actor actor, UUID id, Optional<Long> ifMatch, Function<Product, Uni<?>> change) {
        return Uni.createFrom().deferred(() -> {
            actor.require(Capability.MODERATE_PRODUCTS);
            return access.inTenant(actor.tenantId(), id).flatMap(product -> apply(product, ifMatch, change));
        });
    }

    /**
     * Adapta uma mutação síncrona do produto ao formato assíncrono de
     * {@code change} dos métodos {@code mutate*}.
     */
    static Function<Product, Uni<?>> sync(Consumer<Product> mutation) {
        return product -> Uni.createFrom().voidItem().invoke(() -> mutation.accept(product));
    }

    /**
     * Confere {@code ifMatch} contra a versão lida, executa {@code change} e
     * grava com essa versão como esperada, junto com histórico e eventos
     * pendentes; escrita concorrente lança {@link ConcurrentUpdateException}.
     */
    private Uni<Product> apply(Product product, Optional<Long> ifMatch, Function<Product, Uni<?>> change) {
        long loaded = product.version();
        Versions.requireMatch(ifMatch, loaded);
        return change.apply(product).flatMap(ignored -> products().update(product, loaded)).replaceWith(product);
    }
}
