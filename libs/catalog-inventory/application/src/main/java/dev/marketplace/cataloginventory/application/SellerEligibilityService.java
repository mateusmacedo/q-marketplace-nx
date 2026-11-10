package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import io.smallrye.mutiny.Uni;
import java.util.UUID;

/**
 * Situação KYB/KYC da loja no catálogo. O contexto KYB/KYC é a fonte: a
 * situação só muda pelos eventos dele, e a regra
 * "só seller aprovado tem produto ativo" usa esta cópia local.
 */
public final class SellerEligibilityService {
  /** Nome do consumidor no registro de entregas (inbox). */
  public static final String CONSUMER = "catalog-inventory.seller-eligibility";

  private final SellerEligibilityRepository sellers;

  public SellerEligibilityService(SellerEligibilityRepository sellers) {
    this.sellers = sellers;
  }

  /**
   * Consulta a situação KYB/KYC da loja. O seller lê a própria loja sem
   * capacidade extra; qualquer outra loja exige
   * {@code READ_SELLER_ELIGIBILITY} ({@link AccessDeniedException}). Loja sem
   * veredito registrado é {@code PENDING}.
   */
  public Uni<SellerStatus> get(Actor actor, UUID storeId) {
    return Uni.createFrom().deferred(() -> {
      if (!actor.ownsStore(storeId))
        actor.require(Capability.READ_SELLER_ELIGIBILITY);
      return sellers.statusOf(actor.tenantId(), storeId);
    });
  }

  /**
   * Aplica o veredito do contexto KYB/KYC recebido por evento, sem chamador
   * autenticado. A entrega é deduplicada por
   * {@code eventId} ({@code DUPLICATE} não muda nada) e decisão mais antiga que a
   * registrada é descartada
   * ({@code STALE}); só {@code APPLIED} altera a situação da loja.
   */
  public Uni<SellerVerdict.Outcome> record(SellerVerdict verdict) {
    return Uni.createFrom().deferred(() -> sellers.apply(CONSUMER, verdict));
  }
}
