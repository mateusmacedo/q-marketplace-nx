package dev.marketplace.cataloginventory.persistence;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.application.CategoryRepository;
import dev.marketplace.cataloginventory.application.ConcurrentUpdateException;
import dev.marketplace.cataloginventory.domain.category.AttributeDefinition;
import dev.marketplace.cataloginventory.domain.category.AttributeType;
import dev.marketplace.cataloginventory.domain.category.Category;
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
    private static final String CHECK_VIOLATION = "23514";
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

    private static List<Category> categories(Iterable<Row> rows) {
        return StreamSupport.stream(rows.spliterator(), false).map(row -> Category.restore(row.getUUID("id"),
                row.getUUID("tenant_id"), List.of(row.getArrayOfUUIDs("ancestry")), row.getString("code"),
                row.getString("name"),
                attributes(row.getJsonArray("attributes")), row.getBoolean("active"), row.getLong("version"))).toList();
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
