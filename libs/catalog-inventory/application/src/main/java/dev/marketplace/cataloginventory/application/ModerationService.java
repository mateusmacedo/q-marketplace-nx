package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import io.smallrye.mutiny.Uni;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import static dev.marketplace.cataloginventory.application.ProductSupport.sync;

/**
 * Trust &amp; Safety: fila de moderação, aprovação, rejeição e bloqueio de
 * produtos.
 */
public final class ModerationService {
    private final ProductSupport support;

    public ModerationService(ProductRepository products, CategoryRepository categories,
            SellerEligibilityRepository sellers,
            OfferRepository offers, Clock clock) {
        this.support = new ProductSupport(products, categories, sellers, offers, clock);
    }

    /**
     * Lista a fila de moderação: produtos de todas as lojas do tenant em
     * {@code PENDING_MODERATION}, por cursor ({@code afterId}: último id
     * visto). Exige {@code MODERATE_PRODUCTS}; {@code limit} fora de 1 a 100
     * lança {@link InvalidInputException}.
     */
    public Uni<Page<Product>> queue(Actor actor, Optional<String> afterId, int limit) {
        return Uni.createFrom().deferred(() -> {
            actor.require(Capability.MODERATE_PRODUCTS);
            return Page.fetch(limit, fetchLimit -> support.products().find(actor.tenantId(), Optional.empty(),
                    Optional.of(ProductStatus.PENDING_MODERATION), afterId, fetchLimit),
                    product -> product.id().toString());
        });
    }

    /**
     * Aprova o produto em {@code PENDING_MODERATION} e o torna {@code ACTIVE},
     * reavaliando completude ({@code product-not-publishable}) e seller
     * ({@code seller-not-eligible}). Exige {@code MODERATE_PRODUCTS};
     * {@code ifMatch} ausente = sem checagem.
     */
    public Uni<Product> approve(Actor actor, UUID id, Optional<Long> ifMatch) {
        return support.mutateAsModerator(actor, id, ifMatch, product -> support.publicationOf(product)
                .flatMap(publication -> support.sellerOf(product)
                        .invoke(seller -> product.approve(publication.category().effectiveAttributes(),
                                publication.category().usable(), publication.offeredSkus(), seller, actor.subject(),
                                support.now()))));
    }

    /**
     * Devolve o produto de {@code PENDING_MODERATION} para {@code DRAFT} e
     * registra {@code reason} (obrigatório, até 500 caracteres) no histórico.
     * Exige {@code MODERATE_PRODUCTS}; {@code ifMatch} ausente = sem checagem.
     */
    public Uni<Product> reject(Actor actor, UUID id, Optional<Long> ifMatch, String reason) {
        return support.mutateAsModerator(actor, id, ifMatch,
                sync(product -> product.reject(reason, actor.subject(), support.now())));
    }

    /**
     * Bloqueia o produto (qualquer estado menos {@code BLOCKED} e
     * {@code ARCHIVED}), tira-o da vitrine e publica o evento de bloqueio;
     * {@code reason} obrigatório, até 500 caracteres. Exige
     * {@code MODERATE_PRODUCTS}; {@code ifMatch} ausente = sem checagem.
     */
    public Uni<Product> block(Actor actor, UUID id, Optional<Long> ifMatch, String reason) {
        return support.mutateAsModerator(actor, id, ifMatch,
                sync(product -> product.block(reason, actor.subject(), support.now())));
    }

    /**
     * Desbloqueia o produto, que volta para {@code PENDING_MODERATION} e
     * precisa de nova aprovação; só vale em {@code BLOCKED}. Exige
     * {@code MODERATE_PRODUCTS}; {@code ifMatch} ausente = sem checagem.
     */
    public Uni<Product> unblock(Actor actor, UUID id, Optional<Long> ifMatch) {
        return support.mutateAsModerator(actor, id, ifMatch,
                sync(product -> product.unblock(actor.subject(), support.now())));
    }
}
