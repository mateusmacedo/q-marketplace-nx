package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.Reason;
import java.util.List;
import java.util.UUID;

/**
 * Resultado de uma linha importada. {@code code} e {@code message} só existem
 * em {@code FAILED}, e {@code reasons}, só na recusa de regra de negócio;
 * {@code productId}, quando o produto da linha é conhecido. Textos longos são
 * truncados aos limites do armazenamento.
 */
public record ImportResult(int lineNumber, String sellerSku, ImportOutcome outcome, String code, String message,
    UUID productId, List<Reason> reasons) {
  public static final int SELLER_SKU_MAX_LENGTH = 100;
  public static final int MESSAGE_MAX_LENGTH = 1000;

  public enum ImportOutcome {
    CREATED, UPDATED, UNCHANGED, FAILED
  }

  public ImportResult {
    sellerSku = truncate(sellerSku, SELLER_SKU_MAX_LENGTH);
    message = truncate(message, MESSAGE_MAX_LENGTH);
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }

  public ImportResult(int lineNumber, String sellerSku, ImportOutcome outcome, String code, String message,
      UUID productId) {
    this(lineNumber, sellerSku, outcome, code, message, productId, List.of());
  }

  static ImportResult succeeded(ImportRow row, ImportOutcome outcome, UUID productId) {
    return new ImportResult(row.lineNumber(), row.sellerSku(), outcome, null, null, productId);
  }

  static ImportResult failed(ImportRow row, UUID productId, String code, String message) {
    return failed(row, productId, code, message, List.of());
  }

  static ImportResult failed(ImportRow row, UUID productId, String code, String message, List<Reason> reasons) {
    return new ImportResult(row.lineNumber(), row.sellerSku(), ImportOutcome.FAILED, code, message, productId,
        reasons);
  }

  private static String truncate(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max);
  }
}
