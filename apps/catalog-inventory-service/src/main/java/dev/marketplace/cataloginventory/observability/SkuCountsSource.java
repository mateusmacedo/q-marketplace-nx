package dev.marketplace.cataloginventory.observability;

import io.smallrye.mutiny.Uni;

/**
 * Fonte das contagens de SKU que {@link SkuGauges} publica. WHY: a porta fica em
 * {@code observability} para que a persistência dependa da observabilidade, e não
 * o contrário, sem ciclo entre os pacotes.
 */
public interface SkuCountsSource {
  /**
   * SKUs de produtos com status gravado {@code ACTIVE} e, entre eles, os de loja
   * {@code APPROVED} com saldo disponível ({@code on_hand - reserved}) positivo.
   */
  record SkuCounts(long active, long sellable) {
  }

  /** Conta os SKUs ativos e os vendáveis de todos os tenants. */
  Uni<SkuCounts> skuCounts();
}
