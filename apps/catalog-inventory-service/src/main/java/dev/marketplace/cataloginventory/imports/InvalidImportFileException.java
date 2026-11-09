package dev.marketplace.cataloginventory.imports;

import dev.marketplace.cataloginventory.domain.Reason;
import dev.marketplace.cataloginventory.observability.Refusal;
import java.util.List;

/**
 * Arquivo de importação recusado na validação estrutural (codificação, sintaxe,
 * cabeçalho ou quantidade de linhas); {@code reasons} traz os motivos estáveis.
 */
public class InvalidImportFileException extends RuntimeException implements Refusal {
    private final List<Reason> reasons;

    public InvalidImportFileException(String message, List<Reason> reasons) {
        super(message);
        this.reasons = List.copyOf(reasons);
    }

    /** Motivos na forma compacta ({@code missing-column:title}). */
    public List<String> reasons() {
        return reasons.stream().map(Reason::key).toList();
    }

    public List<Reason> details() {
        return reasons;
    }
}
