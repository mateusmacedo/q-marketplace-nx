package dev.marketplace.cataloginventory.cache;

import io.smallrye.mutiny.Uni;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/** Invalidação depois da escrita, comum aos decorators das portas (ADR 024). */
final class CacheInvalidation {
  /** Prioridade dos decorators de invalidação; sem outros decorators nas portas, a ordem entre eles não importa. */
  static final int PRIORITY = 100;

  private CacheInvalidation() {
  }

  /**
   * Devolve o resultado da escrita só depois de {@code invalidate}, no sucesso e na falha. A invalidação não falha
   * ({@link CatalogCache}), então o resultado e a falha da escrita chegam intactos ao chamador.
   */
  static <T> Uni<T> after(CatalogCache cache, Uni<T> write, Supplier<Uni<Void>> invalidate) {
    return when(cache, write, (item, failure) -> true, invalidate);
  }

  /** {@link #after} só quando {@code needed} aceita o resultado ou a falha da escrita. */
  static <T> Uni<T> when(CatalogCache cache, Uni<T> write, BiPredicate<T, Throwable> needed,
      Supplier<Uni<Void>> invalidate) {
    if (!cache.enabled())
      return write;
    return write.onItemOrFailure().call((item, failure) -> needed.test(item, failure)
        ? invalidate.get()
        : Uni.createFrom().voidItem());
  }
}
