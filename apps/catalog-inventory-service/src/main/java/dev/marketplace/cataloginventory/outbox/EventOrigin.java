package dev.marketplace.cataloginventory.outbox;

import dev.marketplace.cataloginventory.observability.Correlation;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import java.util.Optional;

/**
 * Origem de um evento: a correlação de negócio e o contexto de trace W3C
 * ({@code traceparent}) da requisição ou mensagem
 * que o causou. Capturada antes da transação, porque o relay publica depois,
 * fora da requisição.
 */
public record EventOrigin(String correlationId, String traceParent) {
  private static final String VERSION = "00";

  /**
   * Captura a correlação e o {@code traceparent} do span ativo ({@code null} sem
   * span válido);
   * deve rodar antes da transação, pois o relay publica depois, fora da
   * requisição.
   */
  public static EventOrigin current() {
    SpanContext span = Span.current().getSpanContext();
    String traceParent = span.isValid()
        ? VERSION + "-" + span.getTraceId() + "-" + span.getSpanId() + "-" + span.getTraceFlags().asHex()
        : null;
    return new EventOrigin(Correlation.current(), traceParent);
  }

  /**
   * Contexto remoto equivalente ao {@code traceparent} gravado, para servir de
   * pai do span de publicação.
   */
  public static Optional<Context> context(String traceParent) {
    if (traceParent == null)
      return Optional.empty();
    String[] parts = traceParent.split("-");
    if (parts.length != 4 || !VERSION.equals(parts[0]) || parts[3].length() != 2)
      return Optional.empty();
    SpanContext remote = SpanContext.createFromRemoteParent(parts[1], parts[2], TraceFlags.fromHex(parts[3], 0),
        TraceState.getDefault());
    return remote.isValid() ? Optional.of(Context.root().with(Span.wrap(remote))) : Optional.empty();
  }
}
