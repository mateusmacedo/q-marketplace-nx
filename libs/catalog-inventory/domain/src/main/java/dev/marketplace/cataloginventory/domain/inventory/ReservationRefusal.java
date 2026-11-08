package dev.marketplace.cataloginventory.domain.inventory;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import java.util.List;

/**
 * Reserva recusada pelo estado atual (estoque, preço, disponibilidade); o
 * cliente pode recotar e tentar de novo.
 */
public class ReservationRefusal extends DomainRuleViolation {
    public ReservationRefusal(String code, String message, List<String> reasons) {
        super(code, message, reasons);
    }
}
