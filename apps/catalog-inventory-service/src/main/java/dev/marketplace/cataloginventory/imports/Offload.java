package dev.marketplace.cataloginventory.imports;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import java.util.function.Supplier;

/** Trabalho de CPU (ler e converter planilhas) fora do event loop (ADR 009). */
public final class Offload {
  private Offload() {
  }

  /**
   * Executa {@code work} no pool de workers do Mutiny e, se chamado num
   * contexto Vert.x, devolve o resultado a esse contexto, onde o cliente
   * reativo do banco continua a cadeia.
   */
  public static <T> Uni<T> cpu(Supplier<T> work) {
    Context context = Vertx.currentContext();
    Uni<T> offloaded = Uni.createFrom().item(work).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    return context == null
        ? offloaded
        : offloaded.emitOn(task -> context.runOnContext(ignored -> task.run()));
  }
}
