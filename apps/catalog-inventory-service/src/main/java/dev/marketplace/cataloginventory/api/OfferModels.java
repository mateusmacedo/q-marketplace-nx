package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.domain.offer.EffectivePrice;
import dev.marketplace.cataloginventory.domain.offer.Offer;
import dev.marketplace.cataloginventory.domain.offer.Price;
import dev.marketplace.cataloginventory.domain.offer.Promotion;
import dev.marketplace.cataloginventory.domain.offer.PromotionPhase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contratos HTTP de ofertas: preço de/por e promoções de cada SKU. */
public final class OfferModels {
  private OfferModels() {
  }

  public record PriceDto(
      @Schema(description = "Preço \"de\" (riscado); opcional", nullable = true) @Valid MoneyDto listPrice,
      @Schema(description = "Preço \"por\" (de venda)") @NotNull @Valid MoneyDto salePrice) {
    Price toDomain() {
      return new Price(MoneyDto.domainOf(listPrice), salePrice.toDomain());
    }

    static PriceDto of(Price p) {
      return new PriceDto(MoneyDto.of(p.listPrice()), MoneyDto.of(p.salePrice()));
    }
  }

  public record PromotionRequest(@NotNull @Valid MoneyDto salePrice, @NotNull Instant startsAt,
      @NotNull Instant endsAt) {
  }

  public record PromotionResponse(UUID id, MoneyDto salePrice, Instant startsAt, Instant endsAt,
      @Schema(description = "Fase no instante da resposta, pelo relógio do servidor: agendada, vigente ou encerrada; "
          + "só a encerrada não pode ser cancelada") PromotionPhase phase) {
    static PromotionResponse of(Promotion p, Instant now) {
      return new PromotionResponse(p.id(), MoneyDto.of(p.salePrice()), p.startsAt(), p.endsAt(), p.phaseAt(now));
    }
  }

  public record EffectivePriceDto(MoneyDto listPrice, MoneyDto salePrice,
      @Schema(description = "Promoção vigente, se houver", nullable = true) UUID promotionId,
      @Schema(description = "Versão da definição de preço, para o snapshot do checkout") long priceVersion) {
    static EffectivePriceDto of(EffectivePrice p) {
      return new EffectivePriceDto(MoneyDto.of(p.listPrice()), MoneyDto.of(p.salePrice()),
          p.promotionId().orElse(null),
          p.priceVersion());
    }
  }

  public record OfferResponse(UUID skuId, UUID productId, UUID storeId, String currency, PriceDto price,
      EffectivePriceDto effectivePrice, List<PromotionResponse> promotions,
      @Schema(description = "Versão da oferta, usada no If-Match") long version) {
    static OfferResponse of(Offer o, Instant now) {
      return new OfferResponse(o.skuId(), o.productId(), o.storeId(), o.currency(), PriceDto.of(o.price()),
          EffectivePriceDto.of(o.effectivePrice(now)),
          o.promotions().stream().map(p -> PromotionResponse.of(p, now)).toList(), o.version());
    }
  }
}
