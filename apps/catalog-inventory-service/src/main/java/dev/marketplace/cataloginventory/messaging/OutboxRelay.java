package dev.marketplace.cataloginventory.messaging;

import dev.marketplace.cataloginventory.outbox.EventOrigin;
import dev.marketplace.cataloginventory.observability.Lifecycle;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import dev.marketplace.cataloginventory.config.CatalogConfig;
import dev.marketplace.cataloginventory.persistence.PgOutbox;
import dev.marketplace.cataloginventory.persistence.PgOutbox.Delivery;
import dev.marketplace.cataloginventory.persistence.PgOutbox.Pending;
import dev.marketplace.cataloginventory.persistence.PgOutbox.RelayRun;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ApplicationNotRunning;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.TracingMetadata;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * Publica a outbox no Kafka. Só marca o evento como publicado depois da
 * confirmação do broker; falha adia só aquele evento, até
 * {@code catalog.events.max-backoff}, e o estaciona depois de
 * {@code catalog.events.max-attempts} tentativas, sem pausar as outras chaves.
 */
@ApplicationScoped
public class OutboxRelay {
  private static final Logger LOG = Logger.getLogger(OutboxRelay.class);
  private static final AttributeKey<String> TOPIC = AttributeKey.stringKey("messaging.destination.name");
  private static final AttributeKey<String> EVENT_TYPE = AttributeKey.stringKey("event.type");
  private static final AttributeKey<String> EVENT_ID = AttributeKey.stringKey("event.id");
  private static final AttributeKey<Long> WAIT_MS = AttributeKey.longKey("marketplace.outbox.wait_ms");

  private final MutinyEmitter<String> emitter;
  private final PgOutbox outbox;
  private final CatalogConfig.Events config;
  private final Clock clock;
  private final Lifecycle lifecycle;
  private final Counter published;
  private final Counter failures;
  private final Timer lag;
  private final AtomicLong pending = new AtomicLong();
  private final AtomicLong parked = new AtomicLong();
  private final AtomicLong oldestAgeSeconds = new AtomicLong();

  OutboxRelay(@Channel("catalog-events") MutinyEmitter<String> emitter, PgOutbox outbox, CatalogConfig config,
      Clock clock,
      MeterRegistry registry, Lifecycle lifecycle) {
    this.emitter = emitter;
    this.lifecycle = lifecycle;
    this.outbox = outbox;
    this.config = config.events();
    this.clock = clock;
    this.published = Counter.builder("catalog.outbox.published")
        .description("Eventos confirmados pelo Kafka")
        .register(registry);
    this.failures = Counter.builder("catalog.outbox.failures")
        .description("Publicações recusadas ou sem confirmação no prazo")
        .register(registry);
    this.lag = Timer.builder("catalog.outbox.publish.lag")
        .description("Tempo entre a gravação do evento e a confirmação do Kafka")
        .publishPercentiles(0.5, 0.95, 0.99).register(registry);
    Gauge.builder("catalog.outbox.pending", pending, AtomicLong::get)
        .description("Eventos aguardando publicação")
        .register(registry);
    Gauge.builder("catalog.outbox.parked", parked, AtomicLong::get)
        .description("Eventos estacionados depois de esgotar as tentativas")
        .register(registry);
    Gauge.builder("catalog.outbox.oldest.age", oldestAgeSeconds, AtomicLong::get).baseUnit("seconds")
        .description("Idade do evento pendente mais antigo").register(registry);
  }

  /**
   * Publica lotes cheios, até {@code relayMaxBatches} por execução, e atualiza as métricas de pendência; o span
   * recebe {@code marketplace.outbox.published} e {@code .failed}. Falha fica no log.
   */
  // WHY: o primeiro disparo espera o Reactive Messaging ligar o canal; antes
  // disso, criar o emissor falha (SRMSG00019).
  @Scheduled(identity = "outbox-relay", every = "${catalog.events.relay-interval}", delayed = "3s",
      concurrentExecution = SKIP,
      skipExecutionIf = ApplicationNotRunning.class)
  public Uni<Void> relay() {
    Span job = Span.current();
    return lifecycle.job(() -> drain(config.relayMaxBatches(), RelayRun.NONE)
        .invoke(run -> {
          job.setAttribute("marketplace.outbox.published", run.published());
          job.setAttribute("marketplace.outbox.failed", run.failed());
        })
        .invoke(this::afterRun)
        .flatMap(ignored -> outbox.backlog())
        .invoke(backlog -> {
          pending.set(backlog.pending());
          parked.set(backlog.parked());
          oldestAgeSeconds.set(
              backlog.oldest().map(o -> Duration.between(o, clock.instant())
                  .toSeconds()).orElse(0L));
        })
        .onFailure()
        .invoke(e -> LOG.warn("Falha no relay da outbox; nova tentativa no próximo ciclo", e))
        .onFailure().recoverWithNull()
        .replaceWithVoid());
  }

  /**
   * Publica um lote por transação e segue para o próximo só se o lote veio cheio
   * e sem falhas, até {@code remaining} lotes; devolve a soma da execução.
   */
  // WHY: a trava sai no commit de cada lote, e o lote seguinte só lê depois dele;
  // outro pod que tome a vez no intervalo continua do ponto já confirmado, na ordem.
  private Uni<RelayRun> drain(int remaining, RelayRun total) {
    return outbox.relay(config.relayBatch(), clock.instant(), config.maxBackoff(), config.maxAttempts(),
        this::publish).flatMap(run -> {
          count(run);
          RelayRun sum = total.plus(run);
          if (run.handled() < config.relayBatch() || run.failed() > 0 || remaining <= 1)
            return Uni.createFrom().item(sum);
          return drain(remaining - 1, sum);
        });
  }

  /**
   * Publica o lote com chaves diferentes em paralelo e cada tópico e chave em
   * sequência, na ordem da outbox (ver {@link #publishInOrder}).
   */
  private Uni<List<Delivery>> publish(List<Pending> batch) {
    Map<String, List<Pending>> byKey = batch.stream().collect(Collectors.groupingBy(
        event -> event.topic() + '\u0000' + event.key(), LinkedHashMap::new, Collectors.toList()));
    return Multi.createFrom().iterable(byKey.values())
        .onItem().transformToUni(this::publishInOrder).merge(byKey.size())
        .collect().asList()
        .map(groups -> groups.stream().flatMap(List::stream).toList());
  }

  /**
   * Envia em sequência os eventos de um mesmo tópico e chave; depois da primeira
   * falha, os seguintes ficam retidos ({@code Delivery.held}) para a próxima execução.
   */
  // WHY: confirmar o k+1 com o k falho faria o k sair depois dele na nova tentativa,
  // quebrando a ordem do agregado que os consumidores esperam (ADR 013).
  private Uni<List<Delivery>> publishInOrder(List<Pending> events) {
    AtomicBoolean blocked = new AtomicBoolean();
    return Multi.createFrom().iterable(events)
        .onItem().transformToUniAndConcatenate(event -> blocked.get()
            ? Uni.createFrom().item(Delivery.held(event.seq()))
            : send(event).invoke(d -> blocked.set(d.error().isPresent())))
        .collect().asList();
  }

  /**
   * Envia o evento com o id do agregado como chave e os cabeçalhos {@code event-type} e {@code event-id}. Só
   * conta como entregue com a confirmação do broker dentro de {@code publishTimeout}; recusa ou estouro do
   * prazo vira {@code Delivery.failed}, sem propagar.
   */
  private Uni<Delivery> send(Pending event) {
    RecordHeaders headers = new RecordHeaders();
    headers.add("event-type", event.eventType().getBytes(StandardCharsets.UTF_8));
    headers.add("event-id", event.id().toString().getBytes(StandardCharsets.UTF_8));
    Message<String> message = Message.of(event.payload())
        .addMetadata(OutgoingKafkaRecordMetadata.<String>builder()
            .withTopic(event.topic()).withKey(event.key()).withHeaders(headers)
            .build());
    // WHY: a publicação continua o trace que gravou o evento, não o ciclo do agendador; o span vai do
    // envio à confirmação do broker, é pai do span de produção do Kafka e liga de volta à execução.
    Context parent = EventOrigin.context(event.traceParent()).orElseGet(Context::current);
    Attributes attributes = Attributes.of(TOPIC, event.topic(), EVENT_TYPE, event.eventType(),
        EVENT_ID, event.id().toString(),
        WAIT_MS, Duration.between(event.createdAt(), clock.instant()).toMillis());
    return lifecycle.linked("outbox publish " + event.topic(), parent, attributes,
        traced -> emitter.sendMessage(message.addMetadata(TracingMetadata.withCurrent(traced)))
            .ifNoItem().after(config.publishTimeout()).fail())
        .invoke(() -> lag.record(Duration.between(event.createdAt(), clock.instant())))
        .map(ignored -> Delivery.acked(event.seq()))
        .onFailure().recoverWithItem(e -> Delivery.failed(event.seq(), e));
  }

  /** Soma o lote aos contadores assim que ele é registrado. */
  private void count(RelayRun run) {
    published.increment(run.published());
    failures.increment(run.failed());
  }

  /** Registra no log as falhas da execução; a espera é de cada evento, gravada na outbox. */
  private void afterRun(RelayRun run) {
    if (run.failed() > 0)
      LOG.warnf("%d evento(s) sem confirmação do Kafka; cada um espera a própria nova tentativa, até %ds",
          run.failed(), config.maxBackoff().toSeconds());
  }
}
