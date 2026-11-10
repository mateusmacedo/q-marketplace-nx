package dev.marketplace.cataloginventory.domain.inventory;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.Reason;
import dev.marketplace.cataloginventory.domain.ReasonCode;
import java.util.List;

/**
 * Alteração de saldo incompatível com o estado atual (ex.: físico abaixo do
 * reservado ou SKU ainda com estoque).
 */
public class StockConflictException extends DomainRuleViolation {
  public StockConflictException(ReasonCode code, String message, List<Reason> reasons) {
    super(code, message, reasons);
  }
}
