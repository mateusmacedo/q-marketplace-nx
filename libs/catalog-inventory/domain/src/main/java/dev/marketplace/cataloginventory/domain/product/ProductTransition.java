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
    CREATE(DRAFT, EnumSet.noneOf(ProductStatus.class), TransitionSide.STORE),
    SUBMIT(PENDING_MODERATION, EnumSet.of(DRAFT), TransitionSide.STORE),
    APPROVE(ACTIVE, EnumSet.of(PENDING_MODERATION), TransitionSide.MODERATION),
    REJECT(DRAFT, EnumSet.of(PENDING_MODERATION), TransitionSide.MODERATION),
    BLOCK(BLOCKED, EnumSet.complementOf(EnumSet.of(BLOCKED, ARCHIVED)), TransitionSide.MODERATION),
    UNBLOCK(PENDING_MODERATION, EnumSet.of(BLOCKED), TransitionSide.MODERATION),
    PAUSE(PAUSED_BY_SELLER, EnumSet.of(ACTIVE), TransitionSide.STORE),
    RESUME(ACTIVE, EnumSet.of(PAUSED_BY_SELLER), TransitionSide.STORE),
    ARCHIVE(ARCHIVED, EnumSet.complementOf(EnumSet.of(ARCHIVED)), TransitionSide.STORE),
    REVIEW_REQUIRED(PENDING_MODERATION, publishedStatuses(), TransitionSide.STORE);

    private final ProductStatus to;
    private final Set<ProductStatus> origins;
    private final TransitionSide side;

    ProductTransition(ProductStatus to, Set<ProductStatus> origins, TransitionSide side) {
        this.to = to;
        this.origins = origins;
        this.side = side;
    }

    /** Estado em que o produto fica depois da transição. */
    public ProductStatus to() {
        return to;
    }

    /**
     * Lado que faz a transição: a loja cria, envia, pausa, reativa, arquiva e,
     * ao editar o publicado, devolve à moderação; a moderação decide e bloqueia.
     */
    public TransitionSide side() {
        return side;
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
