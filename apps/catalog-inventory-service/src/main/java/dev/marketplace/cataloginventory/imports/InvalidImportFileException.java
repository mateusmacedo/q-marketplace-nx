package dev.marketplace.cataloginventory.imports;

import dev.marketplace.cataloginventory.observability.Refusal;
import java.util.List;

/**
 * Arquivo de importação recusado na validação estrutural (codificação, sintaxe,
 * cabeçalho ou quantidade de linhas); {@code reasons} traz os motivos estáveis.
 */
public class InvalidImportFileException extends RuntimeException implements Refusal {
    private final List<String> reasons;

    public InvalidImportFileException(String message, List<String> reasons) {
        super(message);
        this.reasons = List.copyOf(reasons);
    }

    public List<String> reasons() {
        return reasons;
    }
}
