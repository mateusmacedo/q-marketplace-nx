package dev.marketplace.cataloginventory.observability;

import io.smallrye.common.vertx.ContextLocals;
import io.smallrye.common.vertx.VertxContext;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Correlação da requisição que originou um evento, guardada no contexto Vert.x
 * local da requisição.
 */
public final class Correlation {
  public static final String HEADER = "X-Correlation-Id";
  private static final String KEY = "catalog.correlation-id";
  private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

  private Correlation() {
  }

  /** Valor informado pelo chamador quando bem formado; senão, um novo. */
  public static String accept(String candidate) {
    return candidate != null && VALID.matcher(candidate).matches() ? candidate : UUID.randomUUID().toString();
  }

  /**
   * Associa a correlação ao contexto da requisição; fora de um contexto Vert.x
   * duplicado não faz
   * nada.
   */
  public static void bind(String correlationId) {
    if (VertxContext.isOnDuplicatedContext())
      ContextLocals.put(KEY, correlationId);
  }

  /**
   * Correlação da requisição atual; fora de uma requisição (agendador,
   * consumidor), uma nova.
   */
  public static String current() {
    if (!VertxContext.isOnDuplicatedContext())
      return UUID.randomUUID().toString();
    return ContextLocals.<String>get(KEY).orElseGet(() -> UUID.randomUUID().toString());
  }
}
