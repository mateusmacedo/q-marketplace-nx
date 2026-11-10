package dev.marketplace.cataloginventory.application;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Linha de dados da planilha já convertida pelo adaptador de formato. Campo
 * vazio chega nulo. {@code error} invalida a linha inteira e, quando ausente,
 * SKU do seller, moeda, preço de venda e estoque estão presentes;
 * {@code productError} só afeta as colunas de produto, que SKU existente ignora.
 */
public record ImportRow(int lineNumber, String productRef, String categoryCode, String title, String description,
    Map<String, String> attributes, String sellerSku, Map<String, String> variantAttributes, Boolean physical,
    BigDecimal heightCm, BigDecimal widthCm, BigDecimal lengthCm, Integer weightGrams, String currency,
    BigDecimal listPrice, BigDecimal salePrice, Integer onHand, String error, String productError) {
  public ImportRow {
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    variantAttributes = variantAttributes == null ? Map.of() : Map.copyOf(variantAttributes);
    if (error == null && (sellerSku == null || currency == null || salePrice == null || onHand == null))
      throw new IllegalArgumentException("Linha convertida exige SKU do seller, moeda, preço de venda e estoque");
  }

  /**
   * Linha que o adaptador não conseguiu converter; guarda o que leu de
   * {@code product_ref} e {@code seller_sku} para agrupar e relatar.
   */
  public static ImportRow invalid(int lineNumber, String productRef, String sellerSku, String error) {
    return new ImportRow(lineNumber, productRef, null, null, null, null, sellerSku, null, null, null, null, null,
        null, null, null, null, null, error, null);
  }
}
