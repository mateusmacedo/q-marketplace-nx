package dev.marketplace.cataloginventory.domain.product;

import java.util.Objects;

/**
 * Motivo pronto marcado numa decisão, com o rótulo que ele tinha naquele momento: renomear ou desativar o motivo
 * depois não muda o que o histórico e os eventos mostram.
 */
public record ReasonLabel(String code, String label) {
    public ReasonLabel {
        Objects.requireNonNull(code, "código obrigatório");
        Objects.requireNonNull(label, "rótulo obrigatório");
    }
}
