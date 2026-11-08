package dev.marketplace.cataloginventory.domain.product;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import static dev.marketplace.cataloginventory.domain.product.ProductStatus.*;

/**
 * Transição do ciclo de publicação, com destino fixo e estados de origem
 * aceitos. Os nomes são gravados e publicados em eventos; não renomear.
 * {@code CREATE} não tem origem (o produto nasce em {@code DRAFT}) e
 * {@code REVIEW_REQUIRED} parte dos estados publicados.
 */
public enum ProductTransition {
    CREATE(DRAFT, EnumSet.noneOf(ProductStatus.class)),
    SUBMIT(PENDING_MODERATION, EnumSet.of(DRAFT)),
    APPROVE(ACTIVE, EnumSet.of(PENDING_MODERATION)),
    REJECT(DRAFT, EnumSet.of(PENDING_MODERATION)),
    BLOCK(BLOCKED, EnumSet.complementOf(EnumSet.of(BLOCKED, ARCHIVED))),
    UNBLOCK(PENDING_MODERATION, EnumSet.of(BLOCKED)),
    PAUSE(PAUSED_BY_SELLER, EnumSet.of(ACTIVE)),
    RESUME(ACTIVE, EnumSet.of(PAUSED_BY_SELLER)),
    ARCHIVE(ARCHIVED, EnumSet.complementOf(EnumSet.of(ARCHIVED))),
    REVIEW_REQUIRED(PENDING_MODERATION, publishedStatuses());

    private final ProductStatus to;
    private final Set<ProductStatus> origins;

    ProductTransition(ProductStatus to, Set<ProductStatus> origins) {
        this.to = to;
        this.origins = origins;
    }

    /** Estado em que o produto fica depois da transição. */
    public ProductStatus to() {
        return to;
    }

    /** Indica se a transição pode partir de {@code status}. */
    public boolean acceptsFrom(ProductStatus status) {
        return origins.contains(status);
    }

    private static Set<ProductStatus> publishedStatuses() {
        return Arrays.stream(ProductStatus.values()).filter(ProductStatus::isPublished)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ProductStatus.class)));
    }
}
