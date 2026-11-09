package dev.marketplace.cataloginventory.domain.product;

/** Lado que faz uma transição: a loja dona do produto ou a moderação do tenant. */
public enum TransitionSide {
    STORE, MODERATION
}
