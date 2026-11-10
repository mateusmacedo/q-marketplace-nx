package dev.marketplace.cataloginventory.domain.inventory;

import java.util.UUID;
import static dev.marketplace.cataloginventory.domain.DomainRuleViolation.invalid;

/**
 * Saldo do SKU: disponível = físico vendável ({@code onHand}) menos reservado;
 * {@code sold} é contador de auditoria.
 */
public record StockLevel(UUID skuId, int onHand, int reserved, long sold, long version) {
  public static final int MAX_ON_HAND = 1_000_000;

  /**
   * Valida o saldo contra os mesmos limites das restrições do banco. Lança
   * {@link IllegalStateException} se algum contador for negativo ou
   * {@code reserved} passar de {@code onHand}.
   */
  public StockLevel {
    // INVARIANT: espelha os CHECKs do banco; nenhuma operação deixa saldo negativo
    // ou reserva acima do físico.
    if (onHand < 0 || reserved < 0 || sold < 0 || reserved > onHand)
      throw new IllegalStateException("Saldo inconsistente para o SKU " + skuId);
  }

  public int available() {
    return onHand - reserved;
  }

  /**
   * Devolve {@code value} se estiver entre 0 e {@link #MAX_ON_HAND}; fora
   * disso recusa com {@code invalid-value}.
   */
  public static int requireOnHand(int value) {
    if (value < 0 || value > MAX_ON_HAND)
      throw invalid("Estoque físico deve estar entre 0 e " + MAX_ON_HAND);
    return value;
  }
}
