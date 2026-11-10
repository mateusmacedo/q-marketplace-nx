package dev.marketplace.cataloginventory.domain.offer;

import dev.marketplace.cataloginventory.domain.DomainReasons;
import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.Reason;
import dev.marketplace.cataloginventory.domain.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import static dev.marketplace.cataloginventory.domain.DomainRuleViolation.invalid;

/**
 * Oferta de um SKU: preço de/por e promoções agendadas. Fica fora do produto
 * para que mudar o preço não dispute a versão
 * do conteúdo. Toda mudança na definição de preço incrementa
 * {@code priceVersion}; {@code version} sustenta o controle
 * otimista de concorrência. A moeda é a do produto e não muda.
 */
public final class Offer {
  public static final int MAX_PROMOTIONS = 20;

  private final UUID skuId;
  private final UUID productId;
  private final UUID tenantId;
  private final UUID storeId;
  private final String currency;
  private final Instant createdAt;
  private Price price;
  private long priceVersion;
  private List<Promotion> promotions;
  private long version;
  private Instant updatedAt;
  private final List<OfferEvent> newEvents = new ArrayList<>();

  /**
   * Ponto único de criação: exige identificadores e datas, valida a moeda e
   * ordena as promoções por início. Recusa com {@code currency-mismatch}
   * preço em outra moeda e {@code invalid-value} moeda fora da ISO 4217, além
   * das promoções incoerentes com o preço.
   */
  private Offer(OfferIdentity identity, Price price, long priceVersion, List<Promotion> promotions, long version,
      Instant createdAt, Instant updatedAt) {
    this.skuId = Objects.requireNonNull(identity.skuId(), "SKU obrigatório");
    this.productId = Objects.requireNonNull(identity.productId(), "produto obrigatório");
    this.tenantId = Objects.requireNonNull(identity.tenantId(), "tenant obrigatório");
    this.storeId = Objects.requireNonNull(identity.storeId(), "loja obrigatória");
    this.currency = Money.requireCurrency(identity.currency());
    this.price = requireCurrency(Objects.requireNonNull(price, "preço obrigatório"));
    this.priceVersion = priceVersion;
    this.promotions = sorted(promotions);
    requirePromotionsConsistent(this.promotions, price);
    this.version = version;
    this.createdAt = Objects.requireNonNull(createdAt, "data de criação obrigatória");
    this.updatedAt = Objects.requireNonNull(updatedAt, "data de atualização obrigatória");
  }

  /**
   * Cria a oferta sem promoções, com {@code priceVersion} 1, e registra
   * {@code PriceChanged}. Recusa com {@code currency-mismatch} se
   * {@code price} não estiver em {@code currency} e com {@code invalid-value}
   * se a moeda não seguir a ISO 4217.
   */
  public static Offer create(UUID skuId, UUID productId, UUID tenantId, UUID storeId, String currency, Price price,
      Instant now) {
    OfferIdentity identity = new OfferIdentity(skuId, productId, tenantId, storeId, currency);
    Offer offer = new Offer(identity, price, 1, List.of(), 0, now, now);
    offer.recordPriceChanged(now);
    return offer;
  }

  /**
   * Reconstrói a oferta persistida preservando versões e datas, sem registrar
   * eventos. Reaplica as mesmas validações da criação.
   */
  public static Offer restore(OfferIdentity identity, Price price, long priceVersion, List<Promotion> promotions,
      long version, Instant createdAt, Instant updatedAt) {
    return new Offer(identity, price, priceVersion, promotions, version, createdAt, updatedAt);
  }

  /**
   * Promoções já encerradas saem da oferta; as pendentes precisam continuar
   * válidas contra o novo preço.
   */
  public void changePrice(Price newPrice, Instant now) {
    requireCurrency(newPrice);
    List<Promotion> pending = pending(now);
    requirePromotionsConsistent(pending, newPrice);
    this.price = newPrice;
    this.promotions = pending;
    newPriceVersion(now);
  }

  /**
   * Agenda a promoção, descarta as encerradas, sobe {@code priceVersion} e
   * registra {@code PriceChanged}. Recusa com {@code promotion-overlap},
   * {@code promotion-above-list-price} e {@code invalid-value} (já terminou,
   * outra moeda ou {@link #MAX_PROMOTIONS} pendentes).
   */
  public void schedulePromotion(Promotion promotion, Instant now) {
    if (promotion.endedBy(now))
      throw invalid("Promoção precisa terminar no futuro");
    List<Promotion> next = new ArrayList<>(pending(now));
    if (next.size() >= MAX_PROMOTIONS)
      throw invalid("Oferta aceita até " + MAX_PROMOTIONS + " promoções pendentes");
    if (next.stream().anyMatch(promotion::overlaps))
      throw new DomainRuleViolation(DomainReasons.PROMOTION_OVERLAP, "Promoção sobrepõe outra já agendada na oferta");
    next.add(promotion);
    requirePromotionsConsistent(next, price);
    this.promotions = sorted(next);
    newPriceVersion(now);
  }

  /**
   * Cancela a promoção pendente ou em vigor, descarta as encerradas, sobe
   * {@code priceVersion} e registra {@code PriceChanged}. Recusa com
   * {@code promotion-not-found} se o id não existir na oferta e
   * {@code promotion-ended} se ela já encerrou.
   */
  public void cancelPromotion(UUID promotionId, Instant now) {
    Promotion target = promotions.stream().filter(p -> p.id().equals(promotionId)).findFirst()
        .orElseThrow(
            () -> new DomainRuleViolation(DomainReasons.PROMOTION_NOT_FOUND, "Promoção não encontrada na oferta"));
    if (target.endedBy(now))
      throw new DomainRuleViolation(DomainReasons.PROMOTION_ENDED, "Promoção já encerrada não pode ser cancelada");
    this.promotions = pending(now).stream().filter(p -> !p.id().equals(promotionId)).toList();
    newPriceVersion(now);
  }

  /**
   * Calcula o preço vigente em {@code now}: o {@code salePrice} da promoção
   * ativa, se houver, ou o do preço base. {@code listPrice} (pode ser nulo) e
   * {@code priceVersion} são sempre os atuais. Não altera a oferta.
   */
  public EffectivePrice effectivePrice(Instant now) {
    Optional<Promotion> active = promotions.stream().filter(p -> p.activeAt(now)).findFirst();
    return new EffectivePrice(price.listPrice(), active.map(Promotion::salePrice).orElse(price.salePrice()),
        active.map(Promotion::id), priceVersion);
  }

  /**
   * Eventos ainda não persistidos; o repositório os grava na outbox, na mesma
   * transação da oferta.
   */
  public List<OfferEvent> drainEvents() {
    List<OfferEvent> drained = List.copyOf(newEvents);
    newEvents.clear();
    return drained;
  }

  /** Adota a versão gravada pelo repositório após persistir a oferta. */
  public void markPersisted(long newVersion) {
    this.version = newVersion;
  }

  public UUID skuId() {
    return skuId;
  }

  public UUID productId() {
    return productId;
  }

  public UUID tenantId() {
    return tenantId;
  }

  public UUID storeId() {
    return storeId;
  }

  public String currency() {
    return currency;
  }

  public Price price() {
    return price;
  }

  public long priceVersion() {
    return priceVersion;
  }

  public List<Promotion> promotions() {
    return promotions;
  }

  public long version() {
    return version;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  /**
   * Fecha uma mudança na definição de preço: sobe {@code priceVersion} e
   * registra o evento.
   */
  private void newPriceVersion(Instant now) {
    priceVersion++;
    recordPriceChanged(now);
  }

  /**
   * Registra {@code PriceChanged} com o preço, as promoções e a
   * {@code priceVersion} atuais e atualiza {@code updatedAt}.
   */
  private void recordPriceChanged(Instant now) {
    newEvents.add(new OfferEvent.PriceChanged(skuId, productId, storeId, price, promotions, priceVersion, now));
    this.updatedAt = now;
  }

  /**
   * Devolve {@code candidate} se estiver na moeda da oferta; senão recusa com
   * {@code currency-mismatch}.
   */
  private Price requireCurrency(Price candidate) {
    if (!candidate.currency().equals(currency))
      throw new DomainRuleViolation(Reason.of(DomainReasons.CURRENCY_MISMATCH, currency),
          "A oferta usa a moeda do produto (" + currency + ")");
    return candidate;
  }

  /**
   * Promoções ainda não encerradas em {@code now}, incluindo a que está em
   * vigor.
   */
  private List<Promotion> pending(Instant now) {
    return promotions.stream().filter(p -> !p.endedBy(now)).toList();
  }

  private static List<Promotion> sorted(List<Promotion> promotions) {
    return promotions.stream().sorted(Comparator.comparing(Promotion::startsAt)).toList();
  }

  /**
   * Exige que cada promoção use a moeda de {@code base}
   * ({@code invalid-value}) e não supere o preço "de", quando houver
   * ({@code promotion-above-list-price}).
   */
  private void requirePromotionsConsistent(List<Promotion> candidates, Price base) {
    for (Promotion p : candidates) {
      if (!p.salePrice().currency().equals(base.currency()))
        throw invalid("Promoção deve usar a moeda da oferta (" + base.currency() + ")");
      if (base.listPrice() != null && p.salePrice().compareAmount(base.listPrice()) > 0)
        throw new DomainRuleViolation(DomainReasons.PROMOTION_ABOVE_LIST_PRICE,
            "Promoção não pode superar o preço \"de\" da oferta");
    }
  }
}
