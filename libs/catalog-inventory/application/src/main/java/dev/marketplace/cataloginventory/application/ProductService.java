package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.product.Dimensions;
import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import dev.marketplace.cataloginventory.domain.product.Sku;
import dev.marketplace.cataloginventory.domain.product.StatusChange;
import io.smallrye.mutiny.Uni;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static dev.marketplace.cataloginventory.application.ProductSupport.sync;

/**
 * Casos de uso do seller sobre o conteúdo, os SKUs e a publicação dos produtos
 * da própria loja.
 */
public final class ProductService {
    public record NewSku(String sellerSku, Map<String, String> variantAttributes, boolean physical,
            Dimensions dimensions) {
    }

    private final ProductSupport support;

    public ProductService(ProductRepository products, CategoryRepository categories,
            SellerEligibilityRepository sellers,
            OfferRepository offers, Clock clock) {
        this.support = new ProductSupport(products, categories, sellers, offers, clock);
    }

    /**
     * Cria o produto em rascunho na loja do seller, gravando histórico e evento
     * na outbox; sem idempotência. Exige {@code MANAGE_OWN_PRODUCTS}; categoria
     * inexistente é 404, inativa {@code category-inactive}; {@code sellerSku}
     * já usado na loja, {@link DuplicateException}.
     */
    public Uni<Product> create(Actor actor, UUID categoryId, String currency, String title, String description,
            Map<String, String> attributes, List<NewSku> skus) {
        return Uni.createFrom().deferred(() -> {
            UUID store = ProductAccess.requireSeller(actor);
            return support.category(actor.tenantId(), categoryId).flatMap(view -> {
                view.requireActive();
                Product product = Product.draft(UUID.randomUUID(), store, view.category(), view.effectiveAttributes(),
                        currency, title,
                        description, attributes, skus.stream().map(ProductService::toSku).toList(), actor.subject(),
                        support.now());
                return support.products().insert(product).replaceWith(product);
            });
        });
    }

    /**
     * Busca o produto sob a visibilidade do chamador: leitor do catálogo do
     * tenant ou moderador vê qualquer loja, o seller só a própria. Inexistente
     * ou fora do alcance lança {@link NotFoundException}.
     */
    public Uni<Product> get(Actor actor, UUID id) {
        return support.access.visible(actor, id);
    }

    /**
     * Lista produtos por cursor ({@code afterId}: último id visto), em ordem de
     * id. Leitor do catálogo ou moderador vê todas as lojas e filtra por
     * {@code storeFilter}; o seller (exige {@code MANAGE_OWN_PRODUCTS}) vê só a
     * própria. {@code limit} fora de 1 a 100 ou filtro {@code OUT_OF_STOCK},
     * estado derivado do saldo que não é gravado: {@link InvalidInputException}.
     */
    public Uni<Page<Product>> list(Actor actor, Optional<ProductStatus> status, Optional<UUID> storeFilter,
            Optional<String> afterId, int limit) {
        return Page.fetch(limit, fetchLimit -> {
            if (status.filter(ProductStatus.OUT_OF_STOCK::equals).isPresent())
                throw new InvalidInputException("OUT_OF_STOCK é derivado do saldo; filtre por ACTIVE e leia o status");
            return support.products().find(actor.tenantId(), ProductAccess.listScope(actor, storeFilter), status,
                    afterId, fetchLimit);
        }, product -> product.id().toString());
    }

    /**
     * Lista as mudanças de estado do produto, da mais antiga para a mais
     * recente, com ator e motivo; visibilidade como em {@link #get}.
     */
    public Uni<List<StatusChange>> history(Actor actor, UUID id) {
        return support.access.visible(actor, id)
                .flatMap(product -> support.products().history(actor.tenantId(), product.id()));
    }

    /**
     * Altera título, descrição, categoria e atributos; produto publicado volta
     * para {@code PENDING_MODERATION}. Recusa {@code BLOCKED}, {@code ARCHIVED}
     * e atributos fora da categoria ({@code invalid-attributes}). Só o seller
     * dono; {@code ifMatch} ausente = sem checagem.
     */
    public Uni<Product> reviseContent(Actor actor, UUID id, Optional<Long> ifMatch, UUID categoryId, String title,
            String description, Map<String, String> attributes) {
        return support.mutateOwned(actor, id, ifMatch, product -> support.category(actor.tenantId(), categoryId)
                .invoke(view -> product.reviseContent(view.requireActive().category(), view.effectiveAttributes(),
                        title, description, attributes, actor.subject(), support.now())));
    }

    /**
     * Acrescenta um SKU; produto publicado volta a {@code PENDING_MODERATION}.
     * {@code sellerSku} repetido: {@code duplicate-seller-sku}; fora da
     * categoria: {@code invalid-attributes}. Só o seller dono; {@code ifMatch}
     * ausente = sem checagem.
     */
    public Uni<Product> addSku(Actor actor, UUID id, Optional<Long> ifMatch, NewSku sku) {
        return support.mutateOwned(actor, id, ifMatch, product -> support.categoryOf(product)
                .invoke(view -> product.addSku(toSku(sku), view.effectiveAttributes(), actor.subject(),
                        support.now())));
    }

    /**
     * Altera variação, indicador físico e dimensões de um SKU; produto
     * publicado volta para {@code PENDING_MODERATION}. SKU alheio:
     * {@code sku-not-found}; físico sem dimensões: {@code missing-dimensions}.
     * Só o seller dono; {@code ifMatch} ausente = sem checagem.
     */
    public Uni<Product> reviseSku(Actor actor, UUID id, UUID skuId, Optional<Long> ifMatch,
            Map<String, String> variantAttributes,
            boolean physical, Dimensions dimensions) {
        return support.mutateOwned(actor, id, ifMatch, product -> support.categoryOf(product)
                .invoke(view -> product.reviseSku(skuId, variantAttributes, physical, dimensions,
                        view.effectiveAttributes(), actor.subject(), support.now())));
    }

    /** A oferta do SKU removido sai junto, porque pertence ao SKU. */
    public Uni<Product> removeSku(Actor actor, UUID id, UUID skuId, Optional<Long> ifMatch) {
        return support.mutateOwned(actor, id, ifMatch, sync(product -> product.removeSku(skuId, support.now())));
    }

    /**
     * Envia o rascunho para {@code PENDING_MODERATION}. Exige produto completo
     * (categoria ativa, oferta em todo SKU, atributos obrigatórios), senão
     * {@code product-not-publishable} com os motivos em {@code reasons}. Só o
     * seller dono; {@code ifMatch} ausente = sem checagem.
     */
    public Uni<Product> submit(Actor actor, UUID id, Optional<Long> ifMatch) {
        return support.mutateOwned(actor, id, ifMatch, product -> support.publicationOf(product)
                .invoke(publication -> product.submit(publication.category().effectiveAttributes(),
                        publication.category().usable(), publication.offeredSkus(), actor.subject(),
                        support.now())));
    }

    /**
     * Pausa a venda ({@code ACTIVE}, esgotado ou não, para
     * {@code PAUSED_BY_SELLER}), tirando o produto da vitrine; outro estado:
     * {@code invalid-transition}. Só o seller dono; {@code ifMatch} ausente =
     * sem checagem.
     */
    public Uni<Product> pause(Actor actor, UUID id, Optional<Long> ifMatch) {
        return support.mutateOwned(actor, id, ifMatch, sync(product -> product.pause(actor.subject(), support.now())));
    }

    /**
     * Retoma o produto pausado ({@code PAUSED_BY_SELLER} para {@code ACTIVE})
     * sem nova moderação, se o seller estiver {@code APPROVED}
     * ({@code seller-not-eligible}). Só o seller dono; {@code ifMatch} ausente
     * = sem checagem.
     */
    public Uni<Product> resume(Actor actor, UUID id, Optional<Long> ifMatch) {
        return support.mutateOwned(actor, id, ifMatch, product -> support.sellerOf(product)
                .invoke(seller -> product.resume(seller, actor.subject(), support.now())));
    }

    /**
     * Arquiva o produto (exclusão lógica) de qualquer estado, menos arquivado;
     * é definitivo e congela edição, oferta e estoque. Só o seller dono;
     * {@code ifMatch} ausente = sem checagem.
     */
    public Uni<Product> archive(Actor actor, UUID id, Optional<Long> ifMatch) {
        return support.mutateOwned(actor, id, ifMatch,
                sync(product -> product.archive(actor.subject(), support.now())));
    }

    private static Sku toSku(NewSku sku) {
        return Sku.create(UUID.randomUUID(), sku.sellerSku(), sku.variantAttributes(), sku.physical(),
                sku.dimensions());
    }
}
