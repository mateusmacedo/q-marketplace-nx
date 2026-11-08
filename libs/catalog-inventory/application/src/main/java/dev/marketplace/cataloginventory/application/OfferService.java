package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.Money;
import dev.marketplace.cataloginventory.domain.offer.Offer;
import dev.marketplace.cataloginventory.domain.offer.Price;
import dev.marketplace.cataloginventory.domain.offer.Promotion;
import dev.marketplace.cataloginventory.domain.product.Product;
import io.smallrye.mutiny.Uni;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Oferta de cada SKU: preço de/por e promoções. Mudar preço não volta o produto
 * para moderação nem disputa a versão do
 * conteúdo; produto arquivado congela as ofertas.
 */
public final class OfferService {
    /**
     * {@code created}: a chamada criou a oferta do SKU, em vez de trocar o preço de
     * uma existente.
     */
    public record Defined(Offer offer, boolean created) {
    }

    private static final String CHANGE_PRICE = "CHANGE_PRICE";
    private final ProductAccess access;
    private final OfferRepository offers;
    private final Clock clock;

    public OfferService(ProductRepository products, OfferRepository offers, Clock clock) {
        this.access = new ProductAccess(products);
        this.offers = offers;
        this.clock = clock;
    }

    /**
     * Busca a oferta do SKU com a visibilidade do produto, como em
     * {@link ProductService#get}; SKU desconhecido ou sem oferta lança
     * {@link NotFoundException}.
     */
    public Uni<Offer> get(Actor actor, UUID skuId) {
        return access.visibleBySku(actor, skuId).flatMap(product -> load(product, skuId));
    }

    /**
     * Cria a oferta do SKU na moeda do produto, ou troca o preço da existente.
     * Trocar exige If-Match, para não sobrescrever
     * outra mudança; If-Match numa oferta que ainda não existe não confere (RFC
     * 9110).
     */
    public Uni<Defined> define(Actor actor, UUID skuId, Optional<Long> ifMatch, Price price) {
        return access.ownedBySku(actor, skuId, CHANGE_PRICE)
                .flatMap(product -> offers.ofSku(product.tenantId(), skuId).flatMap(found -> {
                    Instant now = clock.instant();
                    if (found.isEmpty()) {
                        if (ifMatch.isPresent())
                            throw new VersionMismatchException(0);
                        Offer offer = Offer.create(skuId, product.id(), product.tenantId(), product.storeId(),
                                product.currency(), price, now);
                        return offers.insert(offer).replaceWith(new Defined(offer, true));
                    }
                    if (ifMatch.isEmpty())
                        throw new PreconditionRequiredException();
                    return change(found.get(), ifMatch, offer -> offer.changePrice(price, now))
                            .map(offer -> new Defined(offer, false));
                }));
    }

    /**
     * Agenda uma promoção na oferta do SKU e publica o evento de preço. Só o
     * seller dono; arquivado: {@code invalid-transition}. Recusa sobreposição
     * ({@code promotion-overlap}) e preço acima do de lista. {@code ifMatch}
     * ausente = sem checagem.
     */
    public Uni<Offer> schedulePromotion(Actor actor, UUID skuId, Optional<Long> ifMatch, UUID promotionId,
            Money salePrice,
            Instant startsAt, Instant endsAt) {
        return owned(actor, skuId).flatMap(offer -> change(offer, ifMatch,
                o -> o.schedulePromotion(new Promotion(promotionId, salePrice, startsAt, endsAt), clock.instant())));
    }

    /**
     * Cancela uma promoção pendente ou em vigor e publica o evento de preço;
     * inexistente na oferta: {@code promotion-not-found}, já encerrada:
     * {@code promotion-ended}. Autorização e arquivamento como em
     * {@link #schedulePromotion}.
     */
    public Uni<Offer> cancelPromotion(Actor actor, UUID skuId, UUID promotionId, Optional<Long> ifMatch) {
        return owned(actor, skuId)
                .flatMap(offer -> change(offer, ifMatch, o -> o.cancelPromotion(promotionId, clock.instant())));
    }

    /**
     * Carrega a oferta do SKU para alteração: exige produto da própria loja,
     * não arquivado ({@code invalid-transition}) e com oferta
     * ({@link NotFoundException}).
     */
    private Uni<Offer> owned(Actor actor, UUID skuId) {
        return access.ownedBySku(actor, skuId, CHANGE_PRICE).flatMap(product -> load(product, skuId));
    }

    private Uni<Offer> load(Product product, UUID skuId) {
        return offers.ofSku(product.tenantId(), skuId)
                .map(found -> found.orElseThrow(() -> new NotFoundException("Oferta")));
    }

    /**
     * Confere {@code ifMatch} contra a versão da oferta lida, aplica
     * {@code mutation} e grava com essa versão como esperada; divergente lança
     * {@link VersionMismatchException}, e escrita concorrente,
     * {@link ConcurrentUpdateException}.
     */
    private Uni<Offer> change(Offer offer, Optional<Long> ifMatch, Consumer<Offer> mutation) {
        long loaded = offer.version();
        Versions.requireMatch(ifMatch, loaded);
        mutation.accept(offer);
        return offers.update(offer, loaded).replaceWith(offer);
    }
}
