package dev.marketplace.cataloginventory.persistence;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.application.OfferRepository;
import dev.marketplace.cataloginventory.domain.Money;
import dev.marketplace.cataloginventory.domain.offer.Offer;
import dev.marketplace.cataloginventory.domain.offer.OfferIdentity;
import dev.marketplace.cataloginventory.domain.offer.Price;
import dev.marketplace.cataloginventory.domain.offer.Promotion;
import dev.marketplace.cataloginventory.outbox.EventOrigin;
import dev.marketplace.cataloginventory.outbox.IntegrationEvents;
import dev.marketplace.cataloginventory.outbox.OutboxMessage;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Persiste a oferta e suas promoções numa transação, com os eventos de preço na
 * outbox.
 */
@ApplicationScoped
@Traced
public class PgOfferRepository implements OfferRepository {
  static final String OFFER_COLUMNS = "sku_id, product_id, tenant_id, store_id, currency, list_price, sale_price, price_version, "
      + "version, created_at, updated_at";
  private static final String SKU_ID = "sku_id";
  private static final String SELECT_OFFERS = "SELECT " + OFFER_COLUMNS + " FROM offer WHERE tenant_id = $1";
  private static final String INSERT = """
      INSERT INTO offer (sku_id, product_id, tenant_id, store_id, currency, list_price, sale_price, price_version, version,
                         created_at, updated_at)
      VALUES ($1, $2, $3, $4, $5, $6, $7, $8, 0, $9, $10)""";
  private static final String UPDATE = """
      UPDATE offer SET list_price = $3, sale_price = $4, price_version = $5, version = version + 1, updated_at = $6
      WHERE tenant_id = $1 AND sku_id = $2 AND version = $7""";
  private static final String DELETE_PROMOTIONS = "DELETE FROM offer_promotion WHERE sku_id = $1";
  private static final String INSERT_PROMOTION = "INSERT INTO offer_promotion (id, sku_id, sale_price, starts_at, ends_at) VALUES ($1, $2, $3, $4, $5)";
  private static final String SELECT_PROMOTIONS = """
      SELECT id AS promotion_id, sku_id, sale_price AS promotion_sale_price, starts_at AS promotion_starts_at,
             ends_at AS promotion_ends_at
      FROM offer_promotion WHERE sku_id = ANY ($1::uuid[]) ORDER BY starts_at""";

  private final Pool pool;
  private final PgSupport pg;
  private final PgOutbox outbox;
  private final IntegrationEvents events;

  PgOfferRepository(Pool pool, PgSupport pg, PgOutbox outbox, IntegrationEvents events) {
    this.pool = pool;
    this.pg = pg;
    this.outbox = outbox;
    this.events = events;
  }

  @Override
  public Uni<Optional<Offer>> ofSku(UUID tenantId, UUID skuId) {
    String sql = SELECT_OFFERS + " AND sku_id = $2";
    return pg
        .bounded(pool.preparedQuery(sql).execute(Tuple.of(tenantId, skuId))
            .flatMap(rows -> assemble(pool, rows)))
        .map(found -> found.values().stream().findFirst());
  }

  @Override
  public Uni<List<Offer>> ofProduct(UUID tenantId, UUID productId) {
    String sql = SELECT_OFFERS + " AND product_id = $2";
    return pg
        .bounded(pool.preparedQuery(sql).execute(Tuple.of(tenantId, productId))
            .flatMap(rows -> assemble(pool, rows)))
        .map(found -> List.copyOf(found.values()));
  }

  @Override
  public Uni<Map<UUID, Offer>> ofSkus(UUID tenantId, Collection<UUID> skuIds) {
    if (skuIds.isEmpty())
      return Uni.createFrom().item(Map.of());
    String sql = SELECT_OFFERS + " AND sku_id = ANY ($2::uuid[])";
    return pg.bounded(pool.preparedQuery(sql).execute(Tuple.of(tenantId, skuIds.toArray(UUID[]::new)))
        .flatMap(rows -> assemble(pool, rows)));
  }

  @Override
  public Uni<Void> insert(Offer offer) {
    List<OutboxMessage> messages = events.fromOffer(offer.tenantId(), offer.drainEvents(),
        EventOrigin.current());
    Tuple row = Tuple.tuple().addUUID(offer.skuId()).addUUID(offer.productId()).addUUID(offer.tenantId())
        .addUUID(offer.storeId()).addString(offer.currency()).addValue(listPrice(offer))
        .addValue(offer.price().salePrice().amount())
        .addLong(offer.priceVersion()).addValue(PgSupport.utc(offer.createdAt()))
        .addValue(PgSupport.utc(offer.updatedAt()));
    return pg.bounded(pool.withTransaction(conn -> conn.preparedQuery(INSERT).execute(row)
        .flatMap(ignored -> writePromotions(conn, offer))
        .flatMap(ignored -> outbox.append(conn, messages))))
        .onFailure().transform(e -> PgSupport.unique(e, "offer_pkey", "duplicate-offer",
            "SKU já tem oferta"));
  }

  @Override
  public Uni<Void> update(Offer offer, long expectedVersion) {
    List<OutboxMessage> messages = events.fromOffer(offer.tenantId(), offer.drainEvents(),
        EventOrigin.current());
    Tuple row = Tuple.tuple().addUUID(offer.tenantId()).addUUID(offer.skuId()).addValue(listPrice(offer))
        .addValue(offer.price().salePrice().amount()).addLong(offer.priceVersion())
        .addValue(PgSupport.utc(offer.updatedAt()))
        .addLong(expectedVersion);
    return pg.bounded(pool.withTransaction(conn -> conn.preparedQuery(UPDATE).execute(row)
        .invoke(PgSupport::requireOneRow)
        .flatMap(ignored -> conn.preparedQuery(DELETE_PROMOTIONS)
            .execute(Tuple.of(offer.skuId())))
        .flatMap(ignored -> writePromotions(conn, offer))
        .flatMap(ignored -> outbox.append(conn, messages))))
        .invoke(() -> offer.markPersisted(expectedVersion + 1));
  }

  /**
   * Ofertas das linhas informadas, com promoções carregadas numa segunda
   * consulta, indexadas pelo SKU.
   */
  static Uni<Map<UUID, Offer>> assemble(SqlClient client, RowSet<Row> offerRows) {
    List<Row> rows = new ArrayList<>();
    offerRows.forEach(rows::add);
    if (rows.isEmpty())
      return Uni.createFrom().item(Map.of());
    UUID[] skuIds = rows.stream().map(r -> r.getUUID(SKU_ID)).toArray(UUID[]::new);
    return client.preparedQuery(SELECT_PROMOTIONS).execute(Tuple.of(skuIds)).map(promotionRows -> {
      Map<UUID, String> currencyBySku = new HashMap<>();
      rows.forEach(r -> currencyBySku.put(r.getUUID(SKU_ID), r.getString("currency")));
      Map<UUID, List<Promotion>> promotions = new HashMap<>();
      promotionRows.forEach(p -> promotions
          .computeIfAbsent(p.getUUID(SKU_ID), k -> new ArrayList<>())
          .add(toPromotion(p, currencyBySku.get(p.getUUID(SKU_ID)))));
      Map<UUID, Offer> offers = new LinkedHashMap<>();
      rows.forEach(r -> offers.put(r.getUUID(SKU_ID),
          toOffer(r, promotions.getOrDefault(r.getUUID(SKU_ID), List.of()))));
      return offers;
    });
  }

  /**
   * Reconstrói a oferta da linha com as promoções já carregadas; todos os valores
   * usam a moeda da
   * oferta.
   */
  static Offer toOffer(Row r, List<Promotion> promotions) {
    String currency = r.getString("currency");
    BigDecimal list = r.getBigDecimal("list_price");
    Price price = new Price(list == null ? null : new Money(list, currency),
        new Money(r.getBigDecimal("sale_price"), currency));
    OfferIdentity identity = new OfferIdentity(r.getUUID(SKU_ID), r.getUUID("product_id"), r.getUUID("tenant_id"),
        r.getUUID("store_id"), currency);
    return Offer.restore(identity, price, r.getLong("price_version"), promotions, r.getLong("version"),
        instant(r, "created_at"), instant(r, "updated_at"));
  }

  /** Reconstrói a promoção das colunas {@code promotion_*}, na moeda da oferta. */
  static Promotion toPromotion(Row p, String currency) {
    return new Promotion(p.getUUID("promotion_id"),
        new Money(p.getBigDecimal("promotion_sale_price"), currency),
        PgSupport.instant(p.getOffsetDateTime("promotion_starts_at")),
        PgSupport.instant(p.getOffsetDateTime("promotion_ends_at")));
  }

  /**
   * Insere em lote as promoções da oferta na transação da conexão; sem promoções,
   * não consulta o
   * banco.
   */
  private static Uni<Void> writePromotions(SqlConnection conn, Offer offer) {
    List<Tuple> rows = offer.promotions().stream()
        .map(p -> Tuple.of(p.id(), offer.skuId(), p.salePrice().amount(),
            PgSupport.utc(p.startsAt()), PgSupport.utc(p.endsAt())))
        .toList();
    return PgSupport.batch(conn, INSERT_PROMOTION, rows);
  }

  private static BigDecimal listPrice(Offer offer) {
    Money list = offer.price().listPrice();
    return list == null ? null : list.amount();
  }

  private static Instant instant(Row r, String column) {
    return PgSupport.instant(r.getOffsetDateTime(column));
  }
}
