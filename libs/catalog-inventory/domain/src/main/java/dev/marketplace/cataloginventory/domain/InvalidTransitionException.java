package dev.marketplace.cataloginventory.domain;

/** Transição de estado não permitida a partir do estado atual do agregado. */
public class InvalidTransitionException extends DomainRuleViolation {
    /**
     * Cria a recusa com código {@code invalid-transition}; {@code transition}
     * nomeia a ação tentada e {@code currentState} o estado em que o agregado
     * estava.
     */
    public InvalidTransitionException(String transition, String currentState) {
        super(DomainReasons.INVALID_TRANSITION, "Transição " + transition + " não é permitida no estado " + currentState);
    }
}
