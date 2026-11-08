package dev.marketplace.cataloginventory.domain.inventory;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import java.util.List;

/**
 * Alteração de saldo incompatível com o estado atual (ex.: físico abaixo do
 * reservado ou SKU ainda com estoque).
 */
public class StockConflictException extends DomainRuleViolation {
    public StockConflictException(String code, String message, List<String> reasons) {
        super(code, message, reasons);
    }
}
