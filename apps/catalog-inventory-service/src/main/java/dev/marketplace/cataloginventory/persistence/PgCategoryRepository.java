package dev.marketplace.cataloginventory.persistence;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.application.CategoryNode;
import dev.marketplace.cataloginventory.application.CategoryRepository;
import dev.marketplace.cataloginventory.application.ConcurrentUpdateException;
import dev.marketplace.cataloginventory.domain.category.AttributeDefinition;
import dev.marketplace.cataloginventory.domain.category.AttributeType;
import dev.marketplace.cataloginventory.domain.category.Category;
import dev.marketplace.cataloginventory.domain.category.CategoryNotEmptyException;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import io.vertx.pgclient.PgException;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;
import java.util.stream.StreamSupport;

@ApplicationScoped
@Traced
public class PgCategoryRepository implements CategoryRepository {
  private static final String COLUMNS = "id, tenant_id, ancestry, code, name, attributes, active, version";
  private static final String CHAIN = "SELECT " + COLUMNS + " FROM category WHERE tenant_id = $1"
      + " AND id = ANY ((SELECT ancestry || id FROM category WHERE tenant_id = $1 AND id = $2)::uuid[])"
      + " ORDER BY cardinality(ancestry)";
  private static final String ID_BY_CODE = "SELECT id FROM category WHERE tenant_id = $1 AND code = $2";
  private static final String DESCENDANT_ATTRIBUTE_CODES = """
      SELECT DISTINCT a->>'code' AS code FROM category c, jsonb_array_elements(c.attributes) a
      WHERE c.tenant_id = $1 AND c.ancestry @> ARRAY[$2]::uuid[]""";
  private static final String INSERT = """
      INSERT INTO category (id, tenant_id, parent_id, ancestry, code, name, attributes, active, version)
      VALUES ($1, $2, $3, $4::uuid[], $5, $6, $7, $8, 0)""";
  private static final String UPDATE = """
      UPDATE category SET name = $3, attributes = $4, active = $5, version = version + 1, updated_at = now()
      WHERE tenant_id = $1 AND id = $2 AND version = $6""";
  private static final String SUBTREE_HEIGHT = """
      SELECT coalesce(max(cardinality(ancestry) - array_position(ancestry, $2::uuid) + 1), 0) AS height
      FROM category WHERE tenant_id = $1 AND ancestry @> ARRAY[$2]::uuid[]""";
  private static final String LOCK_TREE = """
      SELECT pg_advisory_xact_lock(hashtextextended('catalog-inventory.category-tree:' || $1::uuid::text, 0))""";
  private static final String PARENT_PATH = """
      SELECT 1 FROM category WHERE tenant_id = $1 AND id = $2 AND (ancestry || id) = $3::uuid[]""";
  private static final String MOVE = """
      UPDATE category SET parent_id = $3, ancestry = $4::uuid[], version = version + 1, updated_at = now()
      WHERE tenant_id = $1 AND id = $2 AND version = $5 AND ancestry = $6::uuid[]""";
  private static final String REBASE_DESCENDANTS = """
      UPDATE category SET ancestry = $3::uuid[] || ancestry[$4:cardinality(ancestry)], updated_at = now()
      WHERE tenant_id = $1 AND ancestry @> ARRAY[$2]::uuid[]""";
  private static final String CHILD_COUNTS = """
      SELECT parent_id, count(*) AS children FROM category WHERE tenant_id = $1 AND parent_id = ANY ($2::uuid[])
      GROUP BY parent_id""";
  private static final String DEPTHS = """
      SELECT id, cardinality(ancestry) AS depth FROM category WHERE tenant_id = $1 AND id = ANY ($2::uuid[])""";
  // WHY: a recursão desce pelo índice dos filhos montando o caminho de cada categoria, e a chave dos códigos do
  // caminho (CategoryNode.pathKey) dá a ordem da árvore e o cursor; a contagem de filhos roda só na página. Com
  // cursor, ela só desce na categoria que vem depois dele ou é ancestral dele: no COLLATE "C" o espaço vem antes
  // de qualquer caractere de código, então os descendentes ficam logo depois da categoria, e os de uma anterior
  // ao cursor que não o contém também vêm antes dele.
  private static final String TREE = """
      WITH RECURSIVE tree AS (
          SELECT id, ARRAY[id] AS path_ids, ARRAY[code::text] AS path_codes, ARRAY[name::text] AS path_names,
                 ARRAY[active] AS path_active, code::text COLLATE "C" AS path_key
          FROM category WHERE tenant_id = $1 AND parent_id IS NULL
          UNION ALL
          SELECT k.id, t.path_ids || k.id, t.path_codes || k.code::text, t.path_names || k.name::text,
                 t.path_active || k.active, (t.path_key || ' ' || k.code::text) COLLATE "C"
          FROM tree t JOIN category k ON k.tenant_id = $1 AND k.parent_id = t.id%s)
      SELECT c.id, c.tenant_id, c.ancestry, c.code, c.name, c.attributes, c.active, c.version, page.path_ids,
             page.path_codes, page.path_names, page.path_active,
             (SELECT count(*) FROM category k WHERE k.tenant_id = $1 AND k.parent_id = c.id) AS children
      FROM (SELECT * FROM tree WHERE true%s ORDER BY path_key LIMIT $%d) page JOIN category c ON c.id = page.id
      ORDER BY page.path_key""";
  private static final String BY_IDS = """
      SELECT c.id, c.tenant_id, c.ancestry, c.code, c.name, c.attributes, c.active, c.version, chain.path_ids,
             chain.path_codes, chain.path_names, chain.path_active,
             (SELECT count(*) FROM category k WHERE k.tenant_id = $1 AND k.parent_id = c.id) AS children
      FROM category c CROSS JOIN LATERAL (
          SELECT array_agg(a.id ORDER BY cardinality(a.ancestry)) AS path_ids,
                 array_agg(a.code::text ORDER BY cardinality(a.ancestry)) AS path_codes,
                 array_agg(a.name::text ORDER BY cardinality(a.ancestry)) AS path_names,
                 array_agg(a.active ORDER BY cardinality(a.ancestry)) AS path_active
          FROM category a WHERE a.tenant_id = $1 AND a.id = ANY (c.ancestry || c.id)) chain
      WHERE c.tenant_id = $1 AND c.id = ANY ($2::uuid[])
      ORDER BY c.code""";
  private static final String DELETE = "DELETE FROM category WHERE tenant_id = $1 AND id = $2 AND version = $3";
  private static final String CHECK_VIOLATION = "23514";
  private static final String FOREIGN_KEY_VIOLATION = "23503";
  private final Pool pool;
  private final PgSupport pg;

  PgCategoryRepository(Pool pool, PgSupport pg) {
    this.pool = pool;
    this.pg = pg;
  }

  @Override
  public Uni<List<Category>> chain(UUID tenantId, UUID categoryId) {
    return pg.bounded(pool.preparedQuery(CHAIN).execute(Tuple.of(tenantId, categoryId)))
        .map(PgCategoryRepository::categories);
  }

  @Override
  public Uni<Optional<UUID>> idByCode(UUID tenantId, String code) {
    return pg.bounded(pool.preparedQuery(ID_BY_CODE).execute(Tuple.of(tenantId, code)))
        .map(rows -> StreamSupport.stream(rows.spliterator(), false).map(row -> row.getUUID("id")).findFirst());
  }

  @Override
  public Uni<Set<String>> descendantAttributeCodes(UUID tenantId, UUID categoryId) {
    return pg.bounded(pool.preparedQuery(DESCENDANT_ATTRIBUTE_CODES).execute(Tuple.of(tenantId, categoryId)))
        .map(rows -> {
          Set<String> codes = new HashSet<>();
          rows.forEach(row -> codes.add(row.getString("code")));
          return codes;
        });
  }

  @Override
  public Uni<Integer> subtreeHeight(UUID tenantId, UUID categoryId) {
    return pg.bounded(pool.preparedQuery(SUBTREE_HEIGHT).execute(Tuple.of(tenantId, categoryId)))
        .map(rows -> rows.iterator().next().getInteger("height"));
  }

  @Override
  public Uni<List<Category>> children(UUID tenantId, Optional<UUID> parentId, Optional<String> afterCode, int limit) {
    StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM category WHERE tenant_id = $1");
    List<Object> params = new ArrayList<>(List.of(tenantId));
    if (parentId.isPresent()) {
      params.add(parentId.get());
      sql.append(" AND parent_id = $").append(params.size());
    } else {
      sql.append(" AND parent_id IS NULL");
    }
    if (afterCode.isPresent()) {
      params.add(afterCode.get());
      sql.append(" AND code > $").append(params.size());
    }
    params.add(limit);
    sql.append(" ORDER BY code LIMIT $").append(params.size());
    return pg.bounded(pool.preparedQuery(sql.toString()).execute(Tuple.from(params)))
        .map(PgCategoryRepository::categories);
  }

  @Override
  public Uni<Map<UUID, Integer>> childCounts(UUID tenantId, Collection<UUID> categoryIds) {
    return counts(CHILD_COUNTS, tenantId, categoryIds, "parent_id", "children");
  }

  @Override
  public Uni<Map<UUID, Integer>> depths(UUID tenantId, Collection<UUID> categoryIds) {
    return counts(DEPTHS, tenantId, categoryIds, "id", "depth");
  }

  @Override
  public Uni<List<CategoryNode>> tree(UUID tenantId, Optional<String> afterPath, boolean usableOnly, int limit) {
    List<Object> params = new ArrayList<>(List.of(tenantId));
    String descend = "";
    StringBuilder filters = new StringBuilder();
    if (afterPath.isPresent()) {
      params.add(afterPath.get());
      String after = "$" + params.size();
      descend = " AND (t.path_key >= " + after + " OR starts_with(" + after + ", t.path_key || ' '))";
      filters.append(" AND path_key > ").append(after);
    }
    if (usableOnly)
      filters.append(" AND false <> ALL (path_active)");
    params.add(limit);
    return pg.bounded(pool.preparedQuery(TREE.formatted(descend, filters, params.size()))
        .execute(Tuple.from(params)))
        .map(rows -> StreamSupport.stream(rows.spliterator(), false).map(PgCategoryRepository::node).toList());
  }

  @Override
  public Uni<List<CategoryNode>> byIds(UUID tenantId, Collection<UUID> categoryIds) {
    if (categoryIds.isEmpty())
      return Uni.createFrom().item(List.of());
    return pg.bounded(pool.preparedQuery(BY_IDS).execute(Tuple.of(tenantId, categoryIds.toArray(UUID[]::new))))
        .map(rows -> StreamSupport.stream(rows.spliterator(), false).map(PgCategoryRepository::node).toList());
  }

  @Override
  public Uni<Void> delete(Category c, long expectedVersion) {
    return pg.bounded(pool.withTransaction(conn -> conn.preparedQuery(LOCK_TREE).execute(Tuple.of(c.tenantId()))
        .flatMap(locked -> conn.preparedQuery(DELETE).execute(Tuple.of(c.tenantId(), c.id(), expectedVersion)))
        .invoke(PgSupport::requireOneRow)))
        .onFailure().transform(failure -> notEmpty(failure, c))
        .replaceWithVoid();
  }

  @Override
  public Uni<Void> insert(Category c) {
    Tuple values = Tuple.tuple().addUUID(c.id()).addUUID(c.tenantId()).addValue(c.parentId().orElse(null))
        .addValue(c.ancestry().toArray(UUID[]::new)).addString(c.code()).addString(c.name())
        .addValue(json(c.attributes())).addBoolean(c.active());
    return pg.bounded(pool.withTransaction(conn -> lockTree(conn, c)
        .flatMap(locked -> conn.preparedQuery(INSERT).execute(values))))
        .onFailure().transform(e -> PgSupport.unique(e, "category_tenant_code_uk", "duplicate-category-code",
            "Já existe categoria com o código " + c.code() + " neste tenant"))
        .replaceWithVoid();
  }

  @Override
  public Uni<Void> update(Category c, long expectedVersion) {
    Tuple values = Tuple.tuple().addUUID(c.tenantId()).addUUID(c.id()).addString(c.name())
        .addValue(json(c.attributes()))
        .addBoolean(c.active()).addLong(expectedVersion);
    return pg.bounded(pool.preparedQuery(UPDATE).execute(values))
        .invoke(PgSupport::requireOneRow)
        .invoke(() -> c.markPersisted(expectedVersion + 1))
        .replaceWithVoid();
  }

  @Override
  public Uni<Void> move(Category c, List<UUID> previousAncestry, long expectedVersion) {
    List<UUID> subtreePrefix = new ArrayList<>(c.ancestry());
    subtreePrefix.add(c.id());
    Tuple self = Tuple.tuple().addUUID(c.tenantId()).addUUID(c.id()).addValue(c.parentId().orElse(null))
        .addValue(c.ancestry().toArray(UUID[]::new)).addLong(expectedVersion)
        .addValue(previousAncestry.toArray(UUID[]::new));
    Tuple descendants = Tuple.tuple().addUUID(c.tenantId()).addUUID(c.id())
        .addValue(subtreePrefix.toArray(UUID[]::new)).addInteger(previousAncestry.size() + 2);
    return pg.bounded(pool.withTransaction(conn -> lockTree(conn, c)
        .flatMap(locked -> conn.preparedQuery(MOVE).execute(self)).invoke(PgSupport::requireOneRow)
        .flatMap(moved -> conn.preparedQuery(REBASE_DESCENDANTS).execute(descendants))))
        .onFailure().transform(PgCategoryRepository::depthExceeded)
        .invoke(() -> c.markPersisted(expectedVersion + 1))
        .replaceWithVoid();
  }

  /**
   * Serializa até o fim da transação as mudanças de estrutura da árvore do tenant e confere que o caminho
   * gravado do pai ainda é o que a categoria recebeu; divergência falha com {@link ConcurrentUpdateException}.
   */
  private static Uni<Void> lockTree(SqlConnection conn, Category c) {
    Uni<RowSet<Row>> locked = conn.preparedQuery(LOCK_TREE).execute(Tuple.of(c.tenantId()));
    if (c.parentId().isEmpty())
      return locked.replaceWithVoid();
    Tuple parentPath = Tuple.of(c.tenantId(), c.parentId().get(), c.ancestry().toArray(UUID[]::new));
    return locked.flatMap(ignored -> conn.preparedQuery(PARENT_PATH).execute(parentPath))
        .invoke(rows -> {
          if (rows.size() != 1)
            throw new ConcurrentUpdateException();
        })
        .replaceWithVoid();
  }

  /**
   * Traduz a violação de {@code category_depth} em conflito de atualização: o domínio já recusou a
   * profundidade lida, então ela só ocorre se a subárvore cresceu depois da leitura.
   */
  private static Throwable depthExceeded(Throwable failure) {
    if (failure instanceof PgException e && CHECK_VIOLATION.equals(e.getSqlState())
        && "category_depth".equals(e.getConstraint()))
      return new ConcurrentUpdateException();
    return failure;
  }

  /**
   * Traduz as chaves estrangeiras da subcategoria e do produto, que barram a exclusão mesmo quando gravados depois
   * da leitura, na recusa da categoria que não está vazia; demais falhas seguem inalteradas.
   */
  private static Throwable notEmpty(Throwable failure, Category c) {
    if (failure instanceof PgException e && FOREIGN_KEY_VIOLATION.equals(e.getSqlState())) {
      if ("category_parent_fk".equals(e.getConstraint()))
        return CategoryNotEmptyException.hasChildren(c.code());
      if ("product_category_fk".equals(e.getConstraint()))
        return CategoryNotEmptyException.hasProducts(c.code());
    }
    return failure;
  }

  /** Mapa de id para a contagem da consulta, que recebe o tenant e os ids. */
  private Uni<Map<UUID, Integer>> counts(String sql, UUID tenantId, Collection<UUID> ids, String key, String value) {
    if (ids.isEmpty())
      return Uni.createFrom().item(Map.of());
    return pg.bounded(pool.preparedQuery(sql).execute(Tuple.of(tenantId, ids.toArray(UUID[]::new))))
        .map(rows -> {
          Map<UUID, Integer> counts = new HashMap<>();
          rows.forEach(row -> counts.put(row.getUUID(key), row.getLong(value).intValue()));
          return counts;
        });
  }

  private static CategoryNode node(Row row) {
    UUID[] ids = row.getArrayOfUUIDs("path_ids");
    String[] codes = row.getArrayOfStrings("path_codes");
    String[] names = row.getArrayOfStrings("path_names");
    Boolean[] active = row.getArrayOfBooleans("path_active");
    List<CategoryNode.Ancestor> ancestors = new ArrayList<>();
    for (int i = 0; i < ids.length - 1; i++)
      ancestors.add(new CategoryNode.Ancestor(ids[i], codes[i], names[i], active[i]));
    return new CategoryNode(category(row), ancestors, row.getLong("children").intValue());
  }

  private static List<Category> categories(Iterable<Row> rows) {
    return StreamSupport.stream(rows.spliterator(), false).map(PgCategoryRepository::category).toList();
  }

  private static Category category(Row row) {
    return Category.restore(row.getUUID("id"), row.getUUID("tenant_id"), List.of(row.getArrayOfUUIDs("ancestry")),
        row.getString("code"), row.getString("name"), attributes(row.getJsonArray("attributes")),
        row.getBoolean("active"), row.getLong("version"));
  }

  /**
   * Serializa os atributos no formato JSONB da coluna {@code attributes};
   * {@link #attributes} faz
   * o caminho inverso.
   */
  static JsonArray json(List<AttributeDefinition> attributes) {
    JsonArray array = new JsonArray();
    attributes.forEach(a -> array.add(new JsonObject().put("code", a.code()).put("label", a.label())
        .put("type", a.type().name()).put("required", a.required()).put("variant", a.variant())
        .put("options", new JsonArray(new ArrayList<>(a.options())))));
    return array;
  }

  /**
   * Reconstrói as definições de atributo a partir do JSONB da coluna
   * {@code attributes}.
   */
  static List<AttributeDefinition> attributes(JsonArray array) {
    List<AttributeDefinition> definitions = new ArrayList<>();
    for (int i = 0; i < array.size(); i++) {
      JsonObject a = array.getJsonObject(i);
      List<String> options = new ArrayList<>();
      a.getJsonArray("options").forEach(o -> options.add(String.valueOf(o)));
      definitions.add(new AttributeDefinition(a.getString("code"), a.getString("label"),
          AttributeType.valueOf(a.getString("type")),
          a.getBoolean("required"), a.getBoolean("variant"), options));
    }
    return definitions;
  }
}
