package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.category.Category;
import io.smallrye.mutiny.Uni;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface CategoryRepository {
    /** Cadeia da raiz até a categoria; vazia quando ela não existe no tenant. */
    Uni<List<Category>> chain(UUID tenantId, UUID categoryId);

    /** Id da categoria pelo código no tenant, ativa ou não; vazio quando não existe. */
    Uni<Optional<UUID>> idByCode(UUID tenantId, String code);

    /**
     * Códigos de atributo definidos pelas subcategorias, em qualquer nível
     * abaixo da categoria (ela própria fica de fora); vazio quando não há
     * descendentes.
     */
    Uni<Set<String>> descendantAttributeCodes(UUID tenantId, UUID categoryId);

    /** Níveis abaixo da categoria: 0 sem subcategorias, 1 só com filhos diretos, e assim por diante. */
    Uni<Integer> subtreeHeight(UUID tenantId, UUID categoryId);

    /**
     * Subcategorias diretas de {@code parentId}, ou as raízes quando vazio, em
     * ordem de código e incluindo as inativas. {@code afterCode} é o cursor
     * (exclusivo) e {@code limit} o máximo de itens.
     */
    Uni<List<Category>> children(UUID tenantId, Optional<UUID> parentId, Optional<String> afterCode, int limit);

    /** Número de subcategorias diretas de cada categoria pedida; a que não tem nenhuma fica fora do mapa. */
    Uni<Map<UUID, Integer>> childCounts(UUID tenantId, Collection<UUID> categoryIds);

    /** Tamanho do caminho de cada categoria pedida, 0 na raiz; a que não existe no tenant fica fora do mapa. */
    Uni<Map<UUID, Integer>> depths(UUID tenantId, Collection<UUID> categoryIds);

    /**
     * Categorias do tenant na ordem da árvore: o pai antes das subcategorias e os irmãos em ordem de código,
     * inclusive as inativas, salvo com {@code usableOnly}. {@code afterPath} é o cursor (exclusivo), a
     * {@link CategoryNode#pathKey} do último visto, e {@code limit} o máximo de itens.
     */
    Uni<List<CategoryNode>> tree(UUID tenantId, Optional<String> afterPath, boolean usableOnly, int limit);

    /**
     * Categorias pedidas, em ordem de código, com o caminho e o número de subcategorias diretas de cada uma; a que
     * não existe no tenant fica de fora.
     */
    Uni<List<CategoryNode>> byIds(UUID tenantId, Collection<UUID> categoryIds);

    /**
     * Falha com {@link DuplicateException} quando o código já existe no tenant e
     * com {@link ConcurrentUpdateException} quando o caminho do pai mudou.
     */
    Uni<Void> insert(Category category);

    /**
     * Falha com {@link ConcurrentUpdateException} quando a versão persistida não é
     * {@code expectedVersion}.
     */
    Uni<Void> update(Category category, long expectedVersion);

    /**
     * Grava o novo caminho da categoria, incrementando a versão, e troca o prefixo {@code previousAncestry} no
     * caminho dos descendentes, numa só transação. Falha com {@link ConcurrentUpdateException} quando a versão ou
     * o caminho gravados divergem de {@code expectedVersion} e {@code previousAncestry} ou o do novo pai mudou.
     */
    Uni<Void> move(Category category, List<UUID> previousAncestry, long expectedVersion);

    /**
     * Exclui a categoria. Falha com {@link ConcurrentUpdateException} quando ela já não existe ou a versão gravada
     * não é {@code expectedVersion}, e com
     * {@link dev.marketplace.cataloginventory.domain.category.CategoryNotEmptyException} quando ela tem
     * subcategoria ou produto, inclusive arquivado, mesmo que gravados depois da leitura.
     */
    Uni<Void> delete(Category category, long expectedVersion);
}
