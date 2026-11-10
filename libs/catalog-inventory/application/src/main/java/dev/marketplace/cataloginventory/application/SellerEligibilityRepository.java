package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import io.smallrye.mutiny.Uni;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Cópia local da situação KYB/KYC da loja; loja desconhecida é {@code PENDING}.
 */
public interface SellerEligibilityRepository {
  /**
   * Situação atual da loja; loja sem veredito registrado é {@code PENDING},
   * nunca vazio.
   */
  Uni<SellerStatus> statusOf(UUID tenantId, UUID storeId);

  /**
   * Situação de cada loja pedida, numa consulta para todas; a loja sem
   * veredito registrado entra no mapa como {@code PENDING}.
   */
  Uni<Map<UUID, SellerStatus>> statusesOf(UUID tenantId, Collection<UUID> storeIds);

  /**
   * Registra a entrega do {@code consumer} e aplica o veredito numa única
   * transação: entrega repetida não muda nada, e
   * veredito com decisão mais antiga que a registrada é descartado, porque a
   * ordem das mensagens não é garantida.
   */
  Uni<SellerVerdict.Outcome> apply(String consumer, SellerVerdict verdict);
}
