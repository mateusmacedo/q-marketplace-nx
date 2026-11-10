package dev.marketplace.cataloginventory.application;

import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Lotes de importação de produtos, com o arquivo enviado e o resultado por linha. */
public interface ProductImportRepository {
  record Claimed(ProductImport job, String content) {
  }

  /** Lote com as contagens, por desfecho, das linhas que já têm resultado. */
  record Summary(ProductImport job, int created, int updated, int unchanged, int failed) {
    public int processedLines() {
      return created + updated + unchanged + failed;
    }
  }

  /** Posição do cursor da lista de lotes: criação e id do último visto. */
  record Position(Instant createdAt, UUID id) {
  }

  Uni<Void> insert(ProductImport job, String content);

  /** Vazio quando o lote não existe ou é de outra loja ou tenant. */
  Uni<Optional<ProductImport>> findImport(UUID tenantId, UUID storeId, UUID id);

  /**
   * Lote com as contagens gravadas junto dele, sem ler o resultado de cada linha; vazio como em
   * {@link #findImport}.
   */
  Uni<Optional<Summary>> findSummary(UUID tenantId, UUID storeId, UUID id);

  /** Lotes da loja, do mais recente ao mais antigo, depois de {@code after} (exclusivo), até {@code limit}. */
  Uni<List<Summary>> list(UUID tenantId, UUID storeId, Optional<Position> after, int limit);

  /** Resultados já gravados, em ordem de linha. */
  Uni<List<ImportResult>> results(UUID importId);

  /**
   * Passa a {@code PROCESSING} o lote mais antigo pendente ou parado desde
   * antes de {@code staleBefore}, com {@code startedAt = now}; dois chamadores
   * nunca recebem o mesmo lote. Vazio quando não há.
   */
  Uni<Optional<Claimed>> claimNext(Instant now, Instant staleBefore);

  /**
   * Grava os resultados e renova {@code startedAt} numa transação; linha já
   * gravada mantém o primeiro resultado.
   */
  Uni<Void> recordResults(UUID importId, List<ImportResult> results, Instant at);

  Uni<Void> complete(UUID importId, Instant at);
}
