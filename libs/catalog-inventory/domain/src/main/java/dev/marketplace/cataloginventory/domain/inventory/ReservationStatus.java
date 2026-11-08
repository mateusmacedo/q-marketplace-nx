package dev.marketplace.cataloginventory.domain.inventory;

/**
 * COMMITTED, RELEASED e EXPIRED são finais e mutuamente exclusivos; só ACTIVE
 * retém estoque.
 */
public enum ReservationStatus {
    ACTIVE, COMMITTED, RELEASED, EXPIRED
}
