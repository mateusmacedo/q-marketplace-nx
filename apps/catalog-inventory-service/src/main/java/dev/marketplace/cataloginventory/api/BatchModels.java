package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.application.Batch;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Contratos HTTP comuns aos endpoints em lote: o item pedido e o desfecho de
 * cada um. A resposta 200 traz os resultados na ordem do pedido, inclusive
 * quando todos falham.
 */
public final class BatchModels {
  private BatchModels() {
  }

  public record BatchItemRequest(@NotNull UUID id,
      @Schema(
          description = "Versão lida do registro; faz o papel do If-Match do endpoint de um item, e ausente dispensa a checagem",
          nullable = true) Long version) {
    /** Itens do pedido na ordem recebida. */
    static List<Batch.Item> toItems(List<BatchItemRequest> requests) {
      return requests.stream().map(r -> new Batch.Item(r.id(), Optional.ofNullable(r.version()))).toList();
    }
  }

  @Schema(
      description = "SUCCEEDED quando o item foi gravado; FAILED com o problem que o endpoint de um item responderia")
  public enum BatchOutcome {
    SUCCEEDED, FAILED
  }
}
