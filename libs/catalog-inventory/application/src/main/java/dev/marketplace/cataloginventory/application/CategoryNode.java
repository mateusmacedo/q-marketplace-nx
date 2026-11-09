package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.category.Category;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Categoria como as listas a mostram: o caminho desde a raiz, sem ela, e o número de subcategorias diretas.
 */
public record CategoryNode(Category category, List<Ancestor> ancestors, int childCount) {
    // WHY: no COLLATE "C" o espaço vem antes de qualquer caractere de código, então o pai sai antes dos filhos e os
    // filhos antes do irmão seguinte ("a b" antes de "a-b"), como na ordem da árvore.
    static final String PATH_SEPARATOR = " ";

    /** Categoria no caminho, da raiz até o pai. */
    public record Ancestor(UUID id, String code, String name, boolean active) {
        static Ancestor of(Category category) {
            return new Ancestor(category.id(), category.code(), category.name(), category.active());
        }
    }

    public CategoryNode {
        ancestors = List.copyOf(ancestors);
    }

    /** Falso quando a categoria ou um ancestral está inativo. */
    public boolean usable() {
        return category.active() && ancestors.stream().allMatch(Ancestor::active);
    }

    /** Nível na árvore, de 1 (raiz) até {@link Category#MAX_DEPTH}. */
    public int level() {
        return ancestors.size() + 1;
    }

    /** Chave da ordem da árvore: os códigos do caminho, da raiz até a categoria, separados por espaço. */
    String pathKey() {
        return Stream.concat(ancestors.stream().map(Ancestor::code), Stream.of(category.code()))
                .collect(Collectors.joining(PATH_SEPARATOR));
    }
}
