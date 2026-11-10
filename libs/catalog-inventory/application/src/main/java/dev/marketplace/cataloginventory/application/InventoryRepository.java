package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.inventory.SkuCandidate;
import dev.marketplace.cataloginventory.domain.inventory.StockLevel;
import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface InventoryRepository {
  /**
   * Saldos atuais dos SKUs indicados; SKU sem saldo no tenant fica fora do
   * mapa, e coleção vazia resulta em mapa vazio.
   */
  Uni<Map<UUID, StockLevel>> levels(UUID tenantId, Collection<UUID> skuIds);

  /**
   * Define o estoque físico do SKU da loja, com auditoria. Falha com
   * {@link NotFoundException}, com
   * {@link VersionMismatchException} quando {@code expectedVersion} não confere,
   * ou com recusa
   * {@code stock-below-reserved} quando o novo valor é menor que o reservado.
   */
  Uni<StockLevel> adjust(StockAdjustment adjustment, Optional<Long> expectedVersion, String actor, Instant at);

  /**
   * Leitura autoritativa dos SKUs para a decisão de reserva; SKUs inexistentes no
   * tenant ficam de fora.
   */
  Uni<Map<UUID, SkuCandidate>> candidates(UUID tenantId, Collection<UUID> skuIds);
}
