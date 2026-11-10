package dev.marketplace.cataloginventory.domain.inventory;

import dev.marketplace.cataloginventory.domain.Money;
import java.util.Objects;
import java.util.UUID;
import static dev.marketplace.cataloginventory.domain.DomainRuleViolation.invalid;

/** Item pedido na reserva com o preço que o comprador aceitou no checkout. */
public record RequestedItem(UUID skuId, int quantity, Money expectedUnitPrice) {
  public static final int MAX_QUANTITY = 1000;

  /**
   * Exige {@code skuId} (nulo gera {@link NullPointerException}) e recusa com
   * {@code invalid-value} quantidade fora de 1 a {@link #MAX_QUANTITY} ou
   * preço unitário esperado ausente.
   */
  public RequestedItem {
    Objects.requireNonNull(skuId, "SKU obrigatório");
    if (quantity < 1 || quantity > MAX_QUANTITY)
      throw invalid("Quantidade deve estar entre 1 e " + MAX_QUANTITY);
    if (expectedUnitPrice == null)
      throw invalid("Preço unitário esperado é obrigatório");
  }
}
