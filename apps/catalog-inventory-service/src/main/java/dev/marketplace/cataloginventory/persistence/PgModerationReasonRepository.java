package dev.marketplace.cataloginventory.persistence;

import dev.marketplace.cataloginventory.application.ModerationReasonEntry;
import dev.marketplace.cataloginventory.application.ModerationReasonRepository;
import dev.marketplace.cataloginventory.domain.product.ModerationReasonDefinition;
import dev.marketplace.cataloginventory.domain.product.ProductTransition;
import dev.marketplace.cataloginventory.observability.Traced;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;

@ApplicationScoped
@Traced
public class PgModerationReasonRepository implements ModerationReasonRepository {
    private static final String COLUMNS = "tenant_id, code, label, applies_to_reject, applies_to_block, position, active,"
            + " version, created_at, updated_at";
    private static final String CATALOG = "SELECT " + COLUMNS
            + " FROM moderation_reason WHERE tenant_id = $1 ORDER BY position, code";
    private static final String BY_CODE = "SELECT " + COLUMNS + " FROM moderation_reason WHERE tenant_id = $1 AND code = $2";
    private static final String INSERT = "INSERT INTO moderation_reason (" + COLUMNS
            + ") VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)";
    private static final String UPDATE = """
            UPDATE moderation_reason SET label = $3, applies_to_reject = $4, applies_to_block = $5, position = $6,
                   active = $7, version = $8, updated_at = $9
             WHERE tenant_id = $1 AND code = $2 AND version = $10""";
    private final Pool pool;
    private final PgSupport pg;

    PgModerationReasonRepository(Pool pool, PgSupport pg) {
        this.pool = pool;
        this.pg = pg;
    }

    @Override
    public Uni<List<ModerationReasonEntry>> catalog(UUID tenantId) {
        return pg.bounded(pool.preparedQuery(CATALOG).execute(Tuple.of(tenantId)))
                .map(rows -> StreamSupport.stream(rows.spliterator(), false)
                        .map(PgModerationReasonRepository::toEntry).toList());
    }

    @Override
    public Uni<Optional<ModerationReasonEntry>> findByCode(UUID tenantId, String code) {
        return pg.bounded(pool.preparedQuery(BY_CODE).execute(Tuple.of(tenantId, code)))
                .map(rows -> StreamSupport.stream(rows.spliterator(), false)
                        .map(PgModerationReasonRepository::toEntry).findFirst());
    }

    @Override
    public Uni<Void> insert(ModerationReasonEntry r) {
        ModerationReasonDefinition d = r.definition();
        Tuple values = Tuple.tuple().addUUID(r.tenantId()).addString(d.code()).addString(d.label())
                .addBoolean(d.appliesTo(ProductTransition.REJECT)).addBoolean(d.appliesTo(ProductTransition.BLOCK))
                .addInteger(r.position()).addBoolean(d.active()).addLong(r.version())
                .addValue(PgSupport.utc(r.createdAt())).addValue(PgSupport.utc(r.updatedAt()));
        return pg.bounded(pool.preparedQuery(INSERT).execute(values))
                .onFailure().transform(e -> PgSupport.unique(e, "moderation_reason_tenant_code_pk",
                        "duplicate-moderation-reason-code",
                        "Já existe motivo de moderação com o código " + d.code() + " neste tenant"))
                .replaceWithVoid();
    }

    @Override
    public Uni<Void> update(ModerationReasonEntry r, long expectedVersion) {
        ModerationReasonDefinition d = r.definition();
        Tuple values = Tuple.tuple().addUUID(r.tenantId()).addString(d.code()).addString(d.label())
                .addBoolean(d.appliesTo(ProductTransition.REJECT)).addBoolean(d.appliesTo(ProductTransition.BLOCK))
                .addInteger(r.position()).addBoolean(d.active()).addLong(r.version())
                .addValue(PgSupport.utc(r.updatedAt())).addLong(expectedVersion);
        return pg.bounded(pool.preparedQuery(UPDATE).execute(values))
                .invoke(PgSupport::requireOneRow)
                .replaceWithVoid();
    }

    private static ModerationReasonEntry toEntry(Row r) {
        Set<ProductTransition> decisions = EnumSet.noneOf(ProductTransition.class);
        if (r.getBoolean("applies_to_reject"))
            decisions.add(ProductTransition.REJECT);
        if (r.getBoolean("applies_to_block"))
            decisions.add(ProductTransition.BLOCK);
        return new ModerationReasonEntry(r.getUUID("tenant_id"),
                new ModerationReasonDefinition(r.getString("code"), r.getString("label"), decisions,
                        r.getBoolean("active")),
                r.getInteger("position"), r.getLong("version"), PgSupport.instant(r.getOffsetDateTime("created_at")),
                PgSupport.instant(r.getOffsetDateTime("updated_at")));
    }
}
