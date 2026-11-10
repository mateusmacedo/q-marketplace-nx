package dev.marketplace.cataloginventory.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marketplace.cataloginventory.application.CategoryNode;
import dev.marketplace.cataloginventory.application.CategoryRepository;
import dev.marketplace.cataloginventory.domain.category.Category;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Taxonomia em cache (ADR 024): a cadeia até a raiz e as páginas de subcategorias, por tenant, dependentes da geração
 * da taxonomia do tenant, que {@link CategoryCacheInvalidation} incrementa a cada categoria criada, revisada, movida ou
 * excluída.
 * As escritas seguem protegidas pela versão otimista do banco.
 */
@ApplicationScoped
public class TaxonomyCache {
  /** Cursor maior que isto não vira chave: é texto do chamador, e um código de categoria tem até 60 caracteres. */
  private static final int MAX_CURSOR = 120;
  private static final Logger LOG = Logger.getLogger(TaxonomyCache.class);
  private final CatalogCache cache;
  private final ObjectMapper json;

  TaxonomyCache(CatalogCache cache, ObjectMapper json) {
    this.cache = cache;
    this.json = json;
  }

  /**
   * Porta de categorias com {@code chain} e {@code children} pelo cache; as demais leituras e as escritas vão direto
   * a {@code categories}. Com o cache desligado, devolve {@code categories}.
   */
  public CategoryRepository over(CategoryRepository categories) {
    return cache.enabled() ? new Cached(categories) : categories;
  }

  private final class Cached implements CategoryRepository {
    private final CategoryRepository categories;

    Cached(CategoryRepository categories) {
      this.categories = categories;
    }

    /** Cadeia vazia (categoria inexistente) não é guardada, para ids ao acaso não ocuparem o cache. */
    @Override
    public Uni<List<Category>> chain(UUID tenantId, UUID categoryId) {
      return cached(tenantId, CacheKeys.chain(tenantId, categoryId), () -> categories.chain(tenantId, categoryId),
          false);
    }

    @Override
    public Uni<List<Category>> children(UUID tenantId, Optional<UUID> parentId, Optional<String> afterCode,
        int limit) {
      Supplier<Uni<List<Category>>> load = () -> categories.children(tenantId, parentId, afterCode, limit);
      if (afterCode.filter(code -> code.length() > MAX_CURSOR).isPresent())
        return load.get();
      return cached(tenantId, CacheKeys.children(tenantId, parentId, limit, afterCode), load, true);
    }

    @Override
    public Uni<Optional<UUID>> idByCode(UUID tenantId, String code) {
      return categories.idByCode(tenantId, code);
    }

    @Override
    public Uni<Set<String>> descendantAttributeCodes(UUID tenantId, UUID categoryId) {
      return categories.descendantAttributeCodes(tenantId, categoryId);
    }

    @Override
    public Uni<Integer> subtreeHeight(UUID tenantId, UUID categoryId) {
      return categories.subtreeHeight(tenantId, categoryId);
    }

    @Override
    public Uni<Map<UUID, Integer>> childCounts(UUID tenantId, Collection<UUID> categoryIds) {
      return categories.childCounts(tenantId, categoryIds);
    }

    @Override
    public Uni<Map<UUID, Integer>> depths(UUID tenantId, Collection<UUID> categoryIds) {
      return categories.depths(tenantId, categoryIds);
    }

    @Override
    public Uni<List<CategoryNode>> tree(UUID tenantId, Optional<String> afterPath, boolean usableOnly,
        int limit) {
      return categories.tree(tenantId, afterPath, usableOnly, limit);
    }

    @Override
    public Uni<List<CategoryNode>> byIds(UUID tenantId, Collection<UUID> categoryIds) {
      return categories.byIds(tenantId, categoryIds);
    }

    @Override
    public Uni<Void> insert(Category category) {
      return categories.insert(category);
    }

    @Override
    public Uni<Void> update(Category category, long expectedVersion) {
      return categories.update(category, expectedVersion);
    }

    @Override
    public Uni<Void> move(Category category, List<UUID> previousAncestry, long expectedVersion) {
      return categories.move(category, previousAncestry, expectedVersion);
    }

    @Override
    public Uni<Void> delete(Category category, long expectedVersion) {
      return categories.delete(category, expectedVersion);
    }

    private Uni<List<Category>> cached(UUID tenantId, String key, Supplier<Uni<List<Category>>> load,
        boolean keepEmpty) {
      return cache.read(CatalogCache.TAXONOMY, key, CacheKeys.taxonomyGeneration(tenantId), null, null)
          .flatMap(lookup -> switch (lookup.result()) {
            case HIT -> decode(lookup.content(), load);
            case MISS -> load.get().invoke(found -> {
              if (keepEmpty || !found.isEmpty())
                store(key, lookup.content(), found);
            });
            case UNMAPPED, BYPASS -> load.get();
          });
    }

    private Uni<List<Category>> decode(String content, Supplier<Uni<List<Category>>> load) {
      try {
        return Uni.createFrom().item(json.readValue(content, Snapshots.Categories.class).restore());
      } catch (JsonProcessingException | RuntimeException e) {
        LOG.debugf(e, "Retrato da taxonomia ilegível; a consulta vai ao banco");
        return load.get();
      }
    }

    /** A resposta não espera a gravação ({@link CatalogCache#detach}). */
    private void store(String key, String stamp, List<Category> found) {
      try {
        CatalogCache.detach(cache.write(CatalogCache.TAXONOMY, key, stamp,
            json.writeValueAsString(Snapshots.Categories.of(found))));
      } catch (JsonProcessingException e) {
        LOG.debugf(e, "Retrato da taxonomia não serializado; nada é guardado");
      }
    }
  }
}
