package dev.marketplace.cataloginventory.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;

/**
 * Timer com histograma (1 ms a 10 s) do P99 das escritas de inventário e oferta
 * (ADR 017). É métrica própria porque cada família Prometheus tem um só tipo, e
 * buckets em parte de {@code http.server.requests} a deixariam mista. Publica
 * também o bucket exato do limite do SLO de latência (ADR 021), que as regras
 * de {@code catalog-inventory-slo.yaml} leem como {@code le="0.5"}: mudar
 * {@link #SLO_THRESHOLD} exige mudar as regras.
 */
@ApplicationScoped
public class InventoryLatency {
  static final String NAME = "catalog.inventory.update.latency";
  static final Duration SLO_THRESHOLD = Duration.ofMillis(500);
  private final MeterRegistry registry;

  InventoryLatency(MeterRegistry registry) {
    this.registry = registry;
  }

  /**
   * Cronometra cada assinatura de {@code work} até o item ({@code success}) ou
   * a falha ({@code failure}); cancelamento não gera amostra. Só envolve o
   * {@code Uni}, sem bloquear a thread.
   */
  public <T> Uni<T> timed(String operation, Uni<T> work) {
    return Uni.createFrom().deferred(() -> {
      Timer.Sample sample = Timer.start(registry);
      return work.onItemOrFailure().invoke((item, failure) -> sample.stop(
          timer(operation, failure == null ? "success" : "failure")));
    });
  }

  private Timer timer(String operation, String outcome) {
    return Timer.builder(NAME)
        .description("Duração das escritas de inventário e oferta")
        .tags("operation", operation, "outcome", outcome)
        .publishPercentileHistogram()
        .minimumExpectedValue(Duration.ofMillis(1))
        .maximumExpectedValue(Duration.ofSeconds(10))
        .serviceLevelObjectives(SLO_THRESHOLD)
        .register(registry);
  }
}
