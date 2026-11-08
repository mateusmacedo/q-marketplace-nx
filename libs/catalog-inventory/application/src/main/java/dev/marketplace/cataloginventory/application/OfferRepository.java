package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.offer.Offer;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persiste a oferta e suas promoções numa única transação, junto com os eventos
 * de preço.
 */
public interface OfferRepository {
    /**
     * Oferta do SKU com suas promoções; vazio quando o SKU ainda não tem
     * oferta.
     */
    Uni<Optional<Offer>> ofSku(UUID tenantId, UUID skuId);

    /**
     * Ofertas dos SKUs do produto que já têm oferta; SKU sem oferta não
     * aparece, e a lista é vazia quando nenhum tem.
     */
    Uni<List<Offer>> ofProduct(UUID tenantId, UUID productId);

    /** Falha com {@link DuplicateException} quando o SKU já tem oferta. */
    Uni<Void> insert(Offer offer);

    /**
     * Falha com {@link ConcurrentUpdateException} quando a versão persistida não é
     * {@code expectedVersion}.
     */
    Uni<Void> update(Offer offer, long expectedVersion);
}
