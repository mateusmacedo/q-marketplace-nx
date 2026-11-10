package dev.marketplace.cataloginventory.domain.inventory;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.Reason;
import dev.marketplace.cataloginventory.domain.ReasonCode;
import java.util.List;

/**
 * Reserva recusada pelo estado atual (estoque, preço, disponibilidade); o
 * cliente pode recotar e tentar de novo.
 */
public class ReservationRefusal extends DomainRuleViolation {
  public ReservationRefusal(ReasonCode code, String message, List<Reason> reasons) {
    super(code, message, reasons);
  }

  public ReservationRefusal(Reason reason, String message, List<Reason> reasons) {
    super(reason, message, reasons);
  }
}
