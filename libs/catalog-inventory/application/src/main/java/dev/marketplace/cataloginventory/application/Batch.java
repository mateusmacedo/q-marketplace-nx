package dev.marketplace.cataloginventory.application;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Ação em lote sobre registros identificados por id e versão. Cada item passa
 * pelo caso de uso do endpoint de um item, na própria transação; a falha de um
 * não desfaz nem interrompe os outros.
 */
public final class Batch {
  public static final int MAX_ITEMS = 100;

  private Batch() {
  }

  /**
   * Registro do lote; {@code version} faz o papel do {@code If-Match} do
   * endpoint de um item, e ausente dispensa a checagem.
   */
  public record Item(UUID id, Optional<Long> version) {
    public Item {
      Objects.requireNonNull(id, "id obrigatório");
      Objects.requireNonNull(version, "versão opcional, não nula");
    }
  }

  /** Resultado de um item: o registro gravado ou a falha do caso de uso. */
  public record Outcome<T>(UUID id, Optional<T> value, Optional<Throwable> failure) {
    static <T> Outcome<T> succeeded(UUID id, T value) {
      return new Outcome<>(id, Optional.of(value), Optional.empty());
    }

    static <T> Outcome<T> failed(UUID id, Throwable failure) {
      return new Outcome<>(id, Optional.empty(), Optional.of(failure));
    }
  }

  /**
   * Exige de 1 a {@link #MAX_ITEMS} itens sem id repetido; fora disso lança
   * {@link InvalidInputException}.
   */
  static void requireItems(List<Item> items) {
    if (items.isEmpty() || items.size() > MAX_ITEMS)
      throw new InvalidInputException("items deve ter de 1 a " + MAX_ITEMS + " itens");
    Set<UUID> seen = new HashSet<>();
    List<String> repeated = items.stream().map(Item::id).filter(id -> !seen.add(id)).distinct()
        .map(UUID::toString).toList();
    if (!repeated.isEmpty())
      throw new InvalidInputException("items repete ids: " + String.join(", ", repeated));
  }

  /**
   * Roda {@code operation} item a item e devolve os resultados na ordem do
   * pedido; a exceção de um item, inclusive síncrona, vira o resultado dele.
   */
  // WHY: em sequência, para o lote ocupar uma conexão do pool por vez e não tirar a vez das outras requisições.
  static <T> Uni<List<Outcome<T>>> run(List<Item> items, BiFunction<UUID, Optional<Long>, Uni<T>> operation) {
    return Multi.createFrom().iterable(items)
        .onItem().transformToUniAndConcatenate(item -> Uni.createFrom()
            .deferred(() -> operation.apply(item.id(), item.version()))
            .map(value -> Outcome.succeeded(item.id(), value))
            .onFailure().recoverWithItem(failure -> Outcome.<T>failed(item.id(), failure)))
        .collect().asList();
  }

  /**
   * Como {@link #run(List, BiFunction)}, mas roda os itens na ordem de
   * {@code executionOrder} (empates mantêm a ordem do pedido) e devolve os
   * resultados na ordem do pedido. Exige ids sem repetição
   * ({@link #requireItems}).
   */
  static <T> Uni<List<Outcome<T>>> run(List<Item> items, Comparator<Item> executionOrder,
      BiFunction<UUID, Optional<Long>, Uni<T>> operation) {
    return run(items.stream().sorted(executionOrder).toList(), operation).map(outcomes -> {
      Map<UUID, Outcome<T>> byId = new HashMap<>();
      outcomes.forEach(outcome -> byId.put(outcome.id(), outcome));
      return items.stream().map(item -> byId.get(item.id())).toList();
    });
  }
}
