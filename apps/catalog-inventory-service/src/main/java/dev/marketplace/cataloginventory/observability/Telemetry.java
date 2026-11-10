package dev.marketplace.cataloginventory.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import java.util.List;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * Contexto de negócio da operação em curso, gravado com as mesmas chaves no span da operação e no MDC dos logs,
 * para que trace e log se encontrem pelos mesmos atributos; as chaves de negócio costuram o ciclo de vida do agregado
 * entre requisições e execuções do worker. Nenhuma tabela de negócio guarda identificador de trace; só a outbox leva o
 * traceparent da mensagem em trânsito, removido com ela pela limpeza (ADR 021).
 */
public final class Telemetry {
  public static final String TENANT = "marketplace.tenant.id";
  public static final String STORE = "marketplace.store.id";
  public static final String USER = "user.id";
  public static final String ROLES = "user.roles";
  public static final String CORRELATION = "marketplace.correlation.id";
  public static final String PROBLEM_TYPE = "marketplace.problem.type";
  public static final String PROBLEM_REASONS = "marketplace.problem.reasons";
  private static final Logger EVENTS = Logger.getLogger("dev.marketplace.cataloginventory.events");

  private Telemetry() {
  }

  /**
   * Grava o par no span da operação e no MDC; valor nulo ou em branco não grava nada. A operação é a requisição, a
   * mensagem ou a etapa do worker, e não o span do adaptador onde a chave apareceu: a busca pela chave encontra a
   * operação inteira ({@link Lifecycle}).
   */
  public static void tag(String key, Object value) {
    if (value == null || value.toString().isBlank())
      return;
    String text = value.toString();
    Lifecycle.operation().setAttribute(key, text);
    MDC.put(key, text);
  }

  /**
   * Registra a recusa ou o erro da requisição: no span da operação, o código do tipo RFC 9457 (o fim da URI), os
   * motivos e o evento {@code problem} com o status; no MDC, o código e os motivos separados por vírgula, que
   * acompanham a linha de log da requisição.
   */
  public static void problem(int status, String type, List<String> reasons) {
    Span span = Lifecycle.operation();
    tag(PROBLEM_TYPE, type);
    if (reasons != null && !reasons.isEmpty()) {
      span.setAttribute(AttributeKey.stringArrayKey(PROBLEM_REASONS), reasons);
      MDC.put(PROBLEM_REASONS, String.join(",", reasons));
    }
    span.addEvent("problem", Attributes.of(AttributeKey.stringKey("type"), type,
        AttributeKey.longKey("http.response.status_code"), (long) status));
  }

  /**
   * Evento de domínio destinado à outbox: entra no span da operação como evento, com tipo, agregado, tópico e id, e
   * no log de decisões de negócio. É registrado quando o lote é montado, antes do INSERT e do commit; uma transação
   * desfeita não o apaga do span nem do log.
   */
  public static void domainEvent(String eventType, String aggregateId, String topic, String eventId) {
    Lifecycle.operation().addEvent("domain-event", Attributes.of(
        AttributeKey.stringKey("event.type"), eventType,
        AttributeKey.stringKey("aggregate.id"), aggregateId,
        AttributeKey.stringKey("messaging.destination.name"), topic,
        AttributeKey.stringKey("event.id"), eventId));
    EVENTS.infof("Evento %s do agregado %s gravado para %s", eventType, aggregateId, topic);
  }
}
