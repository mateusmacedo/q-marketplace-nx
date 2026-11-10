package dev.marketplace.cataloginventory;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.awaitility.Awaitility;
import static dev.marketplace.cataloginventory.Kafka.WAIT;

/** Guarda os spans encerrados nos perfis com o OpenTelemetry ligado, para os testes conferirem atributos e pais. */
@Singleton
public class RecordedSpans implements SpanProcessor {
  private final List<SpanData> ended = new CopyOnWriteArrayList<>();

  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {
  }

  @Override
  public boolean isStartRequired() {
    return false;
  }

  @Override
  public void onEnd(ReadableSpan span) {
    ended.add(span.toSpanData());
  }

  @Override
  public boolean isEndRequired() {
    return true;
  }

  /** Espera o primeiro span encerrado que satisfaz o filtro. */
  SpanData await(Predicate<SpanData> filter) {
    return Awaitility.await().atMost(WAIT).until(() -> ended.stream().filter(filter).findFirst().orElse(null),
        span -> span != null);
  }

  /** Spans encerrados até agora que satisfazem o filtro. */
  List<SpanData> all(Predicate<SpanData> filter) {
    return ended.stream().filter(filter).toList();
  }
}
