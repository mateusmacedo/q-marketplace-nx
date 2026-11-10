package dev.marketplace.cataloginventory.domain.product;

import dev.marketplace.cataloginventory.domain.Reason;
import java.util.Objects;

/**
 * Pendência de publicação e o campo do produto onde ela se resolve
 * ({@code categoryId}, {@code attributes.cor}, {@code skus[CAM-P].offer},
 * {@code skus[CAM-P].variantAttributes.cor}); o código do SKU não aceita
 * colchetes, então o caminho não é ambíguo.
 */
public record PublishingIssue(Reason reason, String field) {
  public PublishingIssue {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(field, "field");
  }

  static PublishingIssue at(String field, Reason reason) {
    return new PublishingIssue(reason, field);
  }

  static String attribute(String code) {
    return "attributes." + code;
  }

  static String sku(String sellerSku) {
    return "skus[" + sellerSku + "]";
  }

  static String variant(String sellerSku) {
    return sku(sellerSku) + ".variantAttributes";
  }

  static String variant(String sellerSku, String code) {
    return variant(sellerSku) + "." + code;
  }
}
