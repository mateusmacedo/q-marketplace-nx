package dev.marketplace.cataloginventory.application;

import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Página por cursor: {@code nextCursor} é a chave do último item, vazio na
 * última página.
 */
public record Page<T>(List<T> items, Optional<String> nextCursor) {
    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 100;

    public Page {
        items = List.copyOf(items);
    }

    /**
     * Valida o tamanho de página pedido pelo cliente; lança
     * {@link InvalidInputException} quando {@code limit} não está entre 1 e
     * {@link #MAX_SIZE}.
     */
    public static void requireSize(int limit) {
        requireSize(limit, MAX_SIZE);
    }

    /** Como {@link #requireSize(int)}, com o máximo {@code maxSize} da listagem que pagina diferente. */
    static void requireSize(int limit, int maxSize) {
        if (limit < 1 || limit > maxSize)
            throw new InvalidInputException("limit deve estar entre 1 e " + maxSize);
    }

    /**
     * Valida {@code limit}, pede {@code limit + 1} itens a {@code search} (o
     * excedente só indica próxima página) e monta a página. {@code search} roda
     * depois da validação; exceção dele ou de {@code limit} inválido vira falha
     * do {@code Uni}.
     */
    static <T> Uni<Page<T>> fetch(int limit, IntFunction<Uni<List<T>>> search, Function<T, String> key) {
        return fetch(limit, MAX_SIZE, search, key);
    }

    /** Como {@link #fetch(int, IntFunction, Function)}, com {@code limit} de 1 a {@code maxSize}. */
    static <T> Uni<Page<T>> fetch(int limit, int maxSize, IntFunction<Uni<List<T>>> search,
            Function<T, String> key) {
        return Uni.createFrom().deferred(() -> {
            requireSize(limit, maxSize);
            return search.apply(limit + 1).map(found -> of(found, limit, key));
        });
    }

    /**
     * {@code fetched} deve ter até {@code limit + 1} itens; o excedente só indica
     * que existe próxima página.
     */
    public static <T> Page<T> of(List<T> fetched, int limit, Function<T, String> key) {
        if (fetched.size() <= limit)
            return new Page<>(fetched, Optional.empty());
        List<T> page = fetched.subList(0, limit);
        return new Page<>(page, Optional.of(key.apply(page.get(limit - 1))));
    }
}
