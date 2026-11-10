package dev.marketplace.cataloginventory.messaging;

import dev.marketplace.cataloginventory.observability.Correlation;
import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.observability.Telemetry;
import io.opentelemetry.api.trace.Span;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marketplace.cataloginventory.config.CatalogConfig;
import dev.marketplace.cataloginventory.application.SellerEligibilityService;
import dev.marketplace.cataloginventory.application.SellerVerdict;
import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Consome os vereditos do contexto KYB/KYC (contrato em
 * {@code contracts/kyb-kyc/events}, definido por este consumidor). A
 * confirmação da
 * leitura no Kafka só acontece depois do commit; mensagem inválida, ou com
 * falha técnica persistente, vai para a fila
 * de descarte configurada no canal.
 */
@ApplicationScoped
@Traced(entry = true)
public class SellerVerdictConsumer {
  private static final Logger LOG = Logger.getLogger(SellerVerdictConsumer.class);
  static final Map<String, SellerStatus> STATUS_BY_TYPE = Map.of(
      "SellerApproved", SellerStatus.APPROVED,
      "SellerRejected", SellerStatus.REJECTED,
      "ReviewRequired", SellerStatus.PENDING);
  private static final int SUPPORTED_VERSION = 1;

  private final SellerEligibilityService eligibility;
  private final ObjectMapper json;
  private final int retries;
  private final MeterRegistry registry;

  SellerVerdictConsumer(SellerEligibilityService eligibility, ObjectMapper json, CatalogConfig config,
      MeterRegistry registry) {
    this.eligibility = eligibility;
    this.json = json;
    this.retries = config.events().consumerRetries();
    this.registry = registry;
  }

  /**
   * Aplica o veredito KYB/KYC à situação da loja e só confirma a leitura no Kafka
   * depois do commit. Mensagem inválida vai direto para a fila de descarte; falha
   * técnica é retentada com backoff (até {@code consumerRetries}) antes disso. Tipo
   * de evento que o catálogo não usa é confirmado sem efeito, e a reentrega é
   * deduplicada pelo inbox. Correlação, tenant, loja e status vão para o span do
   * consumidor e o MDC, o resultado ({@code marketplace.kyb.outcome}) só para o
   * span, e cada veredito deixa uma linha INFO.
   */
  @Incoming("kyb-seller-verdicts")
  public Uni<Void> consume(String payload) {
    JsonNode envelope;
    Optional<SellerVerdict> verdict;
    try {
      envelope = envelope(payload);
      verdict = verdictOf(envelope);
    } catch (InvalidMessageException e) {
      return Uni.createFrom().failure(e);
    }
    if (verdict.isEmpty())
      return Uni.createFrom().voidItem();
    Correlation.bind(correlationOf(envelope));
    SellerVerdict received = verdict.get();
    Span consumer = Span.current();
    Telemetry.tag(Telemetry.CORRELATION, Correlation.current());
    Telemetry.tag(Telemetry.TENANT, received.tenantId());
    Telemetry.tag(Telemetry.STORE, received.storeId());
    Telemetry.tag("marketplace.kyb.status", received.status());
    return eligibility.applyVerdict(received)
        .onFailure().retry().withBackOff(Duration.ofMillis(200), Duration.ofSeconds(10)).atMost(retries)
        .invoke(outcome -> {
          registry.counter("catalog.kyb.verdicts", "outcome", outcome.name().toLowerCase()).increment();
          consumer.setAttribute("marketplace.kyb.outcome", outcome.name());
          LOG.infof("Veredito %s da loja %s: %s", received.status(), received.storeId(), outcome);
        })
        .replaceWithVoid();
  }

  /**
   * Vazio para tipo de evento que o catálogo não usa; o envelope fica válido para
   * outros consumidores.
   */
  Optional<SellerVerdict> parse(String payload) {
    return verdictOf(envelope(payload));
  }

  /**
   * Lê o payload como envelope JSON; texto que não é JSON ou não é um objeto
   * falha como mensagem inválida.
   */
  private JsonNode envelope(String payload) {
    JsonNode envelope;
    try {
      envelope = json.readTree(payload);
    } catch (Exception e) {
      throw new InvalidMessageException("JSON inválido");
    }
    if (!envelope.isObject())
      throw new InvalidMessageException("Envelope ausente");
    return envelope;
  }

  /** Como {@link #parse(String)}, sobre o envelope já lido. */
  private static Optional<SellerVerdict> verdictOf(JsonNode envelope) {
    SellerStatus status = STATUS_BY_TYPE.get(text(envelope, "eventType"));
    if (status == null)
      return Optional.empty();
    if (envelope.path("eventVersion").asInt(-1) != SUPPORTED_VERSION)
      throw new InvalidMessageException("Versão de evento não suportada: " + envelope.path("eventVersion"));
    return Optional.of(new SellerVerdict(uuid(envelope, "eventId"), uuid(envelope, "tenantId"),
        uuid(envelope.path("data"), "storeId"), status, instant(envelope, "occurredAt")));
  }

  /**
   * Correlação do KYB/KYC, propagada aos eventos que o veredito gerar; inválida
   * ou ausente, uma nova.
   */
  private static String correlationOf(JsonNode envelope) {
    return Correlation.accept(envelope.path("correlationId").asText(null));
  }

  /**
   * Lê o campo obrigatório como texto; ausente, nulo, em branco ou não textual
   * falha como
   * mensagem inválida.
   */
  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank())
      throw new InvalidMessageException("Campo ausente: " + field);
    return value.asText();
  }

  /**
   * Lê o campo obrigatório como UUID; valor malformado falha como mensagem
   * inválida.
   */
  private static UUID uuid(JsonNode node, String field) {
    try {
      return UUID.fromString(text(node, field));
    } catch (IllegalArgumentException e) {
      throw new InvalidMessageException("UUID inválido em " + field);
    }
  }

  /**
   * Lê o campo obrigatório como instante ISO-8601; valor malformado falha como
   * mensagem inválida.
   */
  private static Instant instant(JsonNode node, String field) {
    try {
      return Instant.parse(text(node, field));
    } catch (RuntimeException e) {
      throw new InvalidMessageException("Instante inválido em " + field);
    }
  }

  /** Mensagem que nunca será processável; não adianta tentar de novo. */
  static final class InvalidMessageException extends RuntimeException {
    /**
     * Cria a exceção sem causa nem stack trace, já que a falha é determinística.
     */
    InvalidMessageException(String message) {
      super(message, null, false, false);
    }
  }
}
