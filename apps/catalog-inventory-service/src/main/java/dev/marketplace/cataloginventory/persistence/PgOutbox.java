package dev.marketplace.cataloginventory.persistence;

import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.observability.Telemetry;
import dev.marketplace.cataloginventory.outbox.OutboxMessage;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Outbox transacional: os repositórios gravam eventos na transação da mudança
 * de estado, e o relay os publica depois.
 * Uma trava consultiva do PostgreSQL deixa um único relay ativo entre os pods,
 * o que preserva a ordem de gravação.
 */
@ApplicationScoped
@Traced
public class PgOutbox {
    // WHY: $6::text::json porque o cliente reativo codificaria uma String num
    // parâmetro json como string JSON.
    private static final String INSERT = """
            INSERT INTO outbox_event (id, tenant_id, topic, message_key, event_type, payload, created_at, trace_parent)
            VALUES ($1, $2, $3, $4, $5, $6::text::json, $7, $8)""";
    private static final String TRY_LOCK = "SELECT pg_try_advisory_xact_lock(hashtextextended('catalog-inventory.outbox-relay', 0))";
    // WHY: o NOT EXISTS pula o evento cuja chave tem um anterior pendente em espera ou estacionado; publicá-lo faria
    // o anterior sair depois dele, quebrando a ordem por chave (ADR 013).
    private static final String PENDING = """
            SELECT e.seq, e.id, e.topic, e.message_key, e.event_type, e.payload::text AS payload, e.created_at, e.trace_parent
            FROM outbox_event e
            WHERE e.published_at IS NULL AND e.parked_at IS NULL AND (e.next_attempt_at IS NULL OR e.next_attempt_at <= $2)
              AND NOT EXISTS (
                  SELECT 1 FROM outbox_event b
                  WHERE b.topic = e.topic AND b.message_key = e.message_key AND b.seq < e.seq AND b.published_at IS NULL
                    AND (b.parked_at IS NOT NULL OR b.next_attempt_at > $2))
            ORDER BY e.seq LIMIT $1 FOR UPDATE OF e""";
    private static final String PUBLISHED = "UPDATE outbox_event SET published_at = $2, attempts = attempts + 1, last_error = NULL WHERE seq = ANY ($1::bigint[])";
    // WHY: espera de 2^attempts s, com attempts anterior à falha; o expoente limitado impede que o deslocamento
    // estoure, e o teto efetivo é $4 (maxBackoff, em ms).
    private static final String FAILED = """
            UPDATE outbox_event SET attempts = attempts + 1, last_error = $2,
                next_attempt_at = $3::timestamptz
                    + least($4::bigint, 1000::bigint << least(attempts, 30))::double precision * interval '1 millisecond',
                parked_at = CASE WHEN attempts + 1 >= $5::integer THEN $3::timestamptz END
            WHERE seq = $1""";
    private static final String BACKLOG = """
            SELECT count(*) FILTER (WHERE parked_at IS NULL) AS pending,
                   count(*) FILTER (WHERE parked_at IS NOT NULL) AS parked,
                   min(created_at) FILTER (WHERE parked_at IS NULL) AS oldest
            FROM outbox_event WHERE published_at IS NULL""";
    private static final String PURGE_OUTBOX = """
            DELETE FROM outbox_event WHERE seq IN (SELECT seq FROM outbox_event WHERE published_at < $1 LIMIT $2)""";
    private static final String PURGE_INBOX = """
            DELETE FROM inbox_message WHERE (consumer, event_id) IN
                (SELECT consumer, event_id FROM inbox_message WHERE processed_at < $1 LIMIT $2)""";
    private static final int ERROR_MAX_LENGTH = 500;

    public record Pending(long seq, UUID id, String topic, String key, String eventType, String payload,
            Instant createdAt,
            String traceParent) {
    }

    /**
     * Resultado da publicação de um evento pendente; sem erro, o Kafka confirmou o
     * recebimento.
     */
    public record Delivery(long seq, Optional<String> error, boolean held) {
        public static Delivery acked(long seq) {
            return new Delivery(seq, Optional.empty(), false);
        }

        /**
         * Não enviado porque um evento anterior do mesmo tópico e chave falhou no lote;
         * segue pendente, sem contar tentativa, para não sair antes dele.
         */
        public static Delivery held(long seq) {
            return new Delivery(seq, Optional.empty(), true);
        }

        /** Registra a falha de publicação, com o erro truncado em 500 caracteres. */
        public static Delivery failed(long seq, Throwable cause) {
            String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
            return new Delivery(seq, Optional
                    .of(message.length() > ERROR_MAX_LENGTH ? message.substring(0, ERROR_MAX_LENGTH) : message), false);
        }
    }

    /**
     * Contagem de uma execução; zerada também quando outro pod detém a vez de
     * publicar.
     */
    public record RelayRun(int published, int failed) {
        public static final RelayRun NONE = new RelayRun(0, 0);

        public RelayRun plus(RelayRun other) {
            return new RelayRun(published + other.published, failed + other.failed);
        }

        /** Quantos eventos pendentes o lote leu, publicados ou não. */
        public int handled() {
            return published + failed;
        }
    }

    /**
     * Pendência da outbox: {@code pending} e {@code oldest} contam os não
     * publicados fora os estacionados, que vão em {@code parked}.
     */
    public record Backlog(long pending, long parked, Optional<Instant> oldest) {
    }

    private final Pool pool;
    private final Clock clock;

    PgOutbox(Pool pool, Clock clock) {
        this.pool = pool;
        this.clock = clock;
    }

    /**
     * Grava os eventos na transação da conexão informada; falha desfaz também a
     * mudança de estado. Cada mensagem vira um evento {@code domain-event} no span
     * ativo e uma linha no log de decisões ({@link Telemetry#domainEvent}).
     */
    public Uni<Void> append(SqlConnection conn, List<OutboxMessage> messages) {
        List<Tuple> rows = messages.stream()
                .map(m -> Tuple.tuple().addUUID(m.id()).addUUID(m.tenantId()).addString(m.topic())
                        .addString(m.key()).addString(m.eventType()).addString(m.payload())
                        .addValue(PgSupport.utc(m.createdAt()))
                        .addString(m.traceParent()))
                .toList();
        messages.forEach(m -> Telemetry.domainEvent(m.eventType(), m.key(), m.topic(), m.id().toString()));
        return PgSupport.batch(conn, INSERT, rows);
    }

    /**
     * Publica até {@code batch} eventos na ordem de gravação, numa transação que segura a trava do relay e as
     * linhas até registrar o resultado; pula estacionados, os que esperam nova tentativa e os de chave retida.
     * A falha espera {@code 2^tentativas} s, até {@code maxBackoff}, e estaciona na tentativa {@code maxAttempts};
     * publicado e não marcado (queda no meio) sai de novo, e o consumidor deduplica pelo {@code eventId}.
     */
    public Uni<RelayRun> relay(int batch, Instant now, Duration maxBackoff, int maxAttempts,
            Function<List<Pending>, Uni<List<Delivery>>> publish) {
        return pool.withTransaction(conn -> conn.query(TRY_LOCK).execute().flatMap(lock -> {
            if (!lock.iterator().next().getBoolean(0))
                return Uni.createFrom().item(RelayRun.NONE);
            return conn.preparedQuery(PENDING).execute(Tuple.of(batch, PgSupport.utc(now))).flatMap(rows -> {
                List<Pending> pending = new ArrayList<>();
                rows.forEach(r -> pending.add(pending(r)));
                if (pending.isEmpty())
                    return Uni.createFrom().item(RelayRun.NONE);
                return publish.apply(pending)
                        .flatMap(deliveries -> record(conn, deliveries, now, maxBackoff, maxAttempts));
            });
        }));
    }

    /**
     * Conta os eventos ainda não publicados, separando os estacionados, e informa
     * a criação do mais antigo fora os estacionados.
     */
    public Uni<Backlog> backlog() {
        return pool.query(BACKLOG).execute().map(rows -> {
            Row r = rows.iterator().next();
            return new Backlog(r.getLong("pending"), r.getLong("parked"),
                    Optional.ofNullable(PgSupport.instant(r.getOffsetDateTime("oldest"))));
        });
    }

    /**
     * Remove eventos publicados e entregas consumidas mais antigos que os limites
     * informados, em lotes de {@code batch}, repetidos enquanto vêm cheios e
     * {@code maxDuration} não passou. O prazo vale para as duas tabelas, e cada uma
     * remove pelo menos um lote. Devolve o total removido.
     */
    public Uni<Integer> purge(Instant publishedBefore, Instant processedBefore, int batch, Duration maxDuration) {
        Instant deadline = clock.instant().plus(maxDuration);
        return purgeInBatches(PURGE_OUTBOX, publishedBefore, batch, deadline)
                .flatMap(outbox -> purgeInBatches(PURGE_INBOX, processedBefore, batch, deadline)
                        .map(inbox -> outbox + inbox));
    }

    /**
     * Cada lote é uma instrução própria, que não segura linhas entre um lote e o
     * seguinte.
     */
    private Uni<Integer> purgeInBatches(String sql, Instant before, int batch, Instant deadline) {
        return Multi.createBy().repeating()
                .uni(() -> pool.preparedQuery(sql).execute(Tuple.of(PgSupport.utc(before), batch)))
                .whilst(rows -> rows.rowCount() == batch && clock.instant().isBefore(deadline))
                .map(rows -> rows.rowCount())
                .collect().with(Collectors.summingInt(Integer::intValue));
    }

    /**
     * Marca como publicados, numa só atualização, os eventos confirmados e registra
     * o erro, a tentativa e a espera dos que falharam, estacionando os que chegaram
     * a {@code maxAttempts}, na mesma transação que segura as linhas.
     */
    private static Uni<RelayRun> record(SqlConnection conn, List<Delivery> deliveries, Instant now,
            Duration maxBackoff, int maxAttempts) {
        Long[] acked = deliveries.stream().filter(d -> !d.held() && d.error().isEmpty()).map(Delivery::seq)
                .toArray(Long[]::new);
        List<Tuple> failed = deliveries.stream().filter(d -> d.error().isPresent())
                .map(d -> Tuple.of(d.seq(), d.error().get(), PgSupport.utc(now), maxBackoff.toMillis(), maxAttempts))
                .toList();
        Uni<Void> markAcked = acked.length == 0 ? Uni.createFrom().voidItem()
                : conn.preparedQuery(PUBLISHED).execute(Tuple.of(acked, PgSupport.utc(now))).replaceWithVoid();
        return markAcked
                .flatMap(ignored -> PgSupport.batch(conn, FAILED, failed))
                .map(ignored -> new RelayRun(acked.length, failed.size()));
    }

    private static Pending pending(Row r) {
        return new Pending(r.getLong("seq"), r.getUUID("id"), r.getString("topic"), r.getString("message_key"),
                r.getString("event_type"), r.getString("payload"), PgSupport.instant(r.getOffsetDateTime("created_at")),
                r.getString("trace_parent"));
    }
}
