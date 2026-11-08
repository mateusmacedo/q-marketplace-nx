package dev.marketplace.cataloginventory.application;

import java.util.UUID;

/**
 * Resultado de uma linha importada. {@code code} e {@code message} só existem
 * em {@code FAILED}; {@code productId}, quando o produto da linha é conhecido.
 * Textos longos são truncados aos limites do armazenamento.
 */
public record ImportResult(int lineNumber, String sellerSku, ImportOutcome outcome, String code, String message,
        UUID productId) {
    public static final int SELLER_SKU_MAX_LENGTH = 100;
    public static final int MESSAGE_MAX_LENGTH = 1000;

    public enum ImportOutcome {
        CREATED, UPDATED, UNCHANGED, FAILED
    }

    public ImportResult {
        sellerSku = truncate(sellerSku, SELLER_SKU_MAX_LENGTH);
        message = truncate(message, MESSAGE_MAX_LENGTH);
    }

    static ImportResult succeeded(ImportRow row, ImportOutcome outcome, UUID productId) {
        return new ImportResult(row.lineNumber(), row.sellerSku(), outcome, null, null, productId);
    }

    static ImportResult failed(ImportRow row, UUID productId, String code, String message) {
        return new ImportResult(row.lineNumber(), row.sellerSku(), ImportOutcome.FAILED, code, message, productId);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
