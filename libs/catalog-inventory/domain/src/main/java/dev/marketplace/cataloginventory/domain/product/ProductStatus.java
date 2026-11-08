package dev.marketplace.cataloginventory.domain.product;

// WHY: estados do PRD mais ARCHIVED (exclusão lógica); OUT_OF_STOCK nunca é gravado, é o ACTIVE sem saldo calculado
// na leitura (ADR 012).
public enum ProductStatus {
    DRAFT, PENDING_MODERATION, ACTIVE, PAUSED_BY_SELLER, OUT_OF_STOCK, BLOCKED, ARCHIVED;

    /**
     * Já passou pela moderação e segue publicado: {@code ACTIVE} ou
     * {@code PAUSED_BY_SELLER}. Editar nesses estados devolve o produto à
     * moderação.
     */
    public boolean isPublished() {
        return this == ACTIVE || this == PAUSED_BY_SELLER;
    }

    /**
     * Aparece na vitrine: só {@code ACTIVE}, inclusive quando esgotado, que é
     * derivado do saldo.
     */
    public boolean isVisible() {
        return this == ACTIVE;
    }

    /**
     * Aceita edição de conteúdo: todos os estados, exceto {@code BLOCKED} e
     * {@code ARCHIVED}.
     */
    public boolean isEditable() {
        return this != BLOCKED && this != ARCHIVED;
    }
}
