package dev.marketplace.cataloginventory.domain.category;

import dev.marketplace.cataloginventory.domain.DomainReasons;
import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.Reason;
import java.util.List;

/**
 * Exclusão de categoria que não está vazia: com subcategorias ({@code category-has-children}) ou com produtos,
 * inclusive arquivados ({@code category-has-products}). O código vai também em {@code reasons}.
 */
public class CategoryNotEmptyException extends DomainRuleViolation {
  public static final String HAS_CHILDREN = DomainReasons.CATEGORY_HAS_CHILDREN.code();
  public static final String HAS_PRODUCTS = DomainReasons.CATEGORY_HAS_PRODUCTS.code();

  private CategoryNotEmptyException(DomainReasons code, String message) {
    super(code, message, List.of(Reason.of(code)));
  }

  /** Recusa da categoria {@code categoryCode}, que ainda tem subcategorias. */
  public static CategoryNotEmptyException hasChildren(String categoryCode) {
    return new CategoryNotEmptyException(DomainReasons.CATEGORY_HAS_CHILDREN,
        "Categoria " + categoryCode + " tem subcategorias; exclua ou mova as subcategorias antes");
  }

  /** Recusa da categoria {@code categoryCode}, que ainda tem produtos, inclusive arquivados. */
  public static CategoryNotEmptyException hasProducts(String categoryCode) {
    return new CategoryNotEmptyException(DomainReasons.CATEGORY_HAS_PRODUCTS, "Categoria " + categoryCode
        + " tem produtos, inclusive arquivados; troque a categoria deles ou desative a categoria");
  }
}
