package dev.marketplace.cataloginventory.domain.product;

import dev.marketplace.cataloginventory.domain.Attributes;
import dev.marketplace.cataloginventory.domain.DomainReasons;
import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import static dev.marketplace.cataloginventory.domain.DomainRuleViolation.invalid;

/**
 * Variação vendável do produto: código do seller, atributos de variante e
 * dimensões. Preço fica na oferta do SKU.
 */
public final class Sku {
  public static final String SELLER_SKU_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";
  private static final Pattern SELLER_SKU = Pattern.compile(SELLER_SKU_PATTERN);

  private final UUID id;
  private final String sellerSku;
  private Map<String, String> variantAttributes;
  private boolean physical;
  private Dimensions dimensions;

  /**
   * Exige {@code id} e recusa com {@code invalid-value} código do seller fora
   * de {@link #SELLER_SKU_PATTERN}; atributos e dimensões seguem as regras de
   * {@link #define}.
   */
  private Sku(UUID id, String sellerSku, Map<String, String> variantAttributes, boolean physical,
      Dimensions dimensions) {
    this.id = Objects.requireNonNull(id, "id obrigatório");
    if (sellerSku == null || !SELLER_SKU.matcher(sellerSku).matches())
      throw invalid(
          "SKU do seller aceita letras, dígitos, '.', '_' e '-' (até 64), começando por letra ou dígito");
    this.sellerSku = sellerSku;
    define(variantAttributes, physical, dimensions);
  }

  /**
   * Cria o SKU. Recusa com {@code invalid-value} código do seller fora de
   * {@link #SELLER_SKU_PATTERN} ou dimensões em SKU não físico, e com
   * {@code missing-dimensions} se o SKU físico não tiver dimensões.
   */
  public static Sku create(UUID id, String sellerSku, Map<String, String> variantAttributes, boolean physical,
      Dimensions dimensions) {
    return new Sku(id, sellerSku, variantAttributes, physical, dimensions);
  }

  /**
   * Redefine variante, tipo físico e dimensões, copiando os atributos de
   * forma imutável. Recusa com {@code missing-dimensions} SKU físico sem
   * dimensões e com {@code invalid-value} SKU não físico com dimensões.
   */
  void define(Map<String, String> variantAttributes, boolean physical, Dimensions dimensions) {
    // INVARIANT: PRD — dimensões e peso não podem ser nulos para SKUs físicos.
    if (physical && dimensions == null)
      throw new DomainRuleViolation(DomainReasons.MISSING_DIMENSIONS,
          "SKU físico " + sellerSku + " exige altura, largura, comprimento e peso");
    if (!physical && dimensions != null)
      throw invalid("SKU não físico " + sellerSku + " não tem dimensões");
    this.variantAttributes = Attributes.copy(variantAttributes);
    this.physical = physical;
    this.dimensions = dimensions;
  }

  public UUID id() {
    return id;
  }

  public String sellerSku() {
    return sellerSku;
  }

  public Map<String, String> variantAttributes() {
    return variantAttributes;
  }

  public boolean physical() {
    return physical;
  }

  public Optional<Dimensions> dimensions() {
    return Optional.ofNullable(dimensions);
  }
}
