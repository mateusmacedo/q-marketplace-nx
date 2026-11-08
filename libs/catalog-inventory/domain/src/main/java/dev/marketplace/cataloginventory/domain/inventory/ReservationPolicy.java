package dev.marketplace.cataloginventory.domain.inventory;

import dev.marketplace.cataloginventory.domain.offer.EffectivePrice;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static dev.marketplace.cataloginventory.domain.DomainRuleViolation.invalid;
import static dev.marketplace.cataloginventory.domain.DomainRuleViolation.requireText;

/**
 * Decide se os itens podem ser reservados: produto ativo, seller aprovado e
 * preço vigente da oferta igual ao aceito no checkout.
 * WHY: comparar o preço vigente, e não só a versão, cobre promoções que começam
 * ou terminam sem mudar a definição.
 * O saldo é verificado depois, atomicamente, no armazenamento.
 */
public final class ReservationPolicy {
    public static final int MAX_ITEMS = 100;
    public static final int ORDER_REF_MAX_LENGTH = 100;

    /**
     * Ordem canônica de travamento dos SKUs, igual à do tipo {@code uuid} do
     * PostgreSQL (bytes sem sinal). Toda operação que trava saldo de vários SKUs
     * segue esta ordem, o que impede deadlock entre elas.
     */
    public static final Comparator<UUID> LOCK_ORDER = Comparator
            .comparing(UUID::getMostSignificantBits, Long::compareUnsigned)
            .thenComparing(UUID::getLeastSignificantBits, Long::compareUnsigned);

    private ReservationPolicy() {
    }

    /** Itens na ordem canônica de travamento ({@link #LOCK_ORDER}). */
    public static List<ReservationItem> inLockOrder(Collection<ReservationItem> items) {
        return items.stream().sorted(Comparator.comparing(ReservationItem::skuId, LOCK_ORDER)).toList();
    }

    /**
     * Abre reserva {@code ACTIVE} até {@code now + ttl}. Limite inválido gera
     * {@code invalid-value} e item inelegível, {@link ReservationRefusal} com
     * {@code duplicate-sku}, {@code unknown-sku}, {@code product-unavailable},
     * {@code seller-not-eligible} ou {@code price-changed}.
     */
    public static Reservation open(UUID id, UUID tenantId, String idempotencyKey, String requestHash, String orderRef,
            List<RequestedItem> requested, Map<UUID, SkuCandidate> candidates, Instant now,
            Duration ttl, Duration maxTtl) {
        if (requested == null || requested.isEmpty() || requested.size() > MAX_ITEMS)
            throw invalid("Reserva exige de 1 a " + MAX_ITEMS + " itens");
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(maxTtl) > 0)
            throw invalid("Prazo da reserva deve ser positivo e de até " + maxTtl.toMinutes() + " minutos");
        if (orderRef != null)
            requireText(orderRef, ORDER_REF_MAX_LENGTH, "orderRef tem até " + ORDER_REF_MAX_LENGTH + " caracteres");
        Set<UUID> seen = new HashSet<>();
        List<String> reasons = new ArrayList<>();
        List<ReservationItem> items = new ArrayList<>();
        for (RequestedItem item : requested) {
            if (!seen.add(item.skuId())) {
                reasons.add("duplicate-sku:" + item.skuId());
                continue;
            }
            SkuCandidate candidate = candidates.get(item.skuId());
            if (candidate == null) {
                reasons.add("unknown-sku:" + item.skuId());
                continue;
            }
            if (candidate.productStatus() != ProductStatus.ACTIVE) {
                reasons.add("product-unavailable:" + item.skuId());
                continue;
            }
            if (!candidate.seller().isEligible())
                reasons.add("seller-not-eligible:" + item.skuId());
            // INVARIANT: produto ativo tem oferta em todo SKU; a publicação exige.
            EffectivePrice price = candidate.offer().effectivePrice(now);
            if (!price.salePrice().equals(item.expectedUnitPrice()))
                reasons.add("price-changed:" + item.skuId());
            items.add(new ReservationItem(item.skuId(), candidate.productId(), candidate.storeId(), item.quantity(),
                    price.salePrice(), price.priceVersion()));
        }
        if (!reasons.isEmpty())
            throw refusal(reasons);
        return new Reservation(id, tenantId, idempotencyKey, requestHash, orderRef, ReservationStatus.ACTIVE,
                inLockOrder(items),
                now,
                now.plus(ttl), null);
    }

    /** Recusa por falta de saldo, detectada atomicamente na gravação. */
    public static ReservationRefusal insufficientStock(Collection<UUID> skuIds) {
        return refusal(skuIds.stream().sorted().map(id -> "insufficient-stock:" + id).toList());
    }

    /**
     * Agrupa os motivos pelo prefixo antes de {@code :}; o código é esse
     * prefixo quando só há um e {@code reservation-refused} quando há mais de
     * um.
     */
    static ReservationRefusal refusal(List<String> reasons) {
        Set<String> categories = new TreeSet<>();
        reasons.forEach(r -> categories.add(r.substring(0, r.indexOf(':'))));
        String code = categories.size() == 1 ? categories.iterator().next() : "reservation-refused";
        return new ReservationRefusal(code, "Reserva recusada; recote os itens indicados", reasons);
    }
}
