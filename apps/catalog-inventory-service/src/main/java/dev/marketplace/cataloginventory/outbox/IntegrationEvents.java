package dev.marketplace.cataloginventory.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marketplace.cataloginventory.config.CatalogConfig;
import dev.marketplace.cataloginventory.domain.Money;
import dev.marketplace.cataloginventory.domain.inventory.InventoryEvent;
import dev.marketplace.cataloginventory.domain.inventory.InventoryEvent.InventoryDepleted;
import dev.marketplace.cataloginventory.domain.inventory.InventoryEvent.InventoryReserved;
import dev.marketplace.cataloginventory.domain.inventory.InventoryEvent.InventoryUpdated;
import dev.marketplace.cataloginventory.domain.offer.OfferEvent;
import dev.marketplace.cataloginventory.domain.offer.OfferEvent.PriceChanged;
import dev.marketplace.cataloginventory.domain.product.ProductEvent;
import dev.marketplace.cataloginventory.domain.product.ReasonLabel;
import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import dev.marketplace.cataloginventory.outbox.EventContracts.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Traduz eventos de domínio para o contrato publicado; o modelo interno não sai
 * do serviço (ADR 003).
 */
@ApplicationScoped
public class IntegrationEvents {
  private final ObjectMapper json;
  private final CatalogConfig.Events config;

  IntegrationEvents(ObjectMapper json, CatalogConfig config) {
    this.json = json;
    this.config = config.events();
  }

  /**
   * Converte os eventos de produto em mensagens do tópico de produtos, na ordem
   * recebida; a chave
   * é o id do produto.
   */
  public List<OutboxMessage> fromProduct(UUID tenantId, List<ProductEvent> events, EventOrigin origin) {
    return events.stream().map(event -> switch (event) {
      case ProductEvent.ProductCreated e -> product(tenantId, "ProductCreated", e, origin,
          new ProductCreatedV1(e.productId(), e.storeId(), e.categoryId(), e.currency(), e.title(),
              e.skus().stream().map(s -> new SkuV1(s.skuId(), s.sellerSku())).toList()));
      case ProductEvent.ProductStatusChanged e -> product(tenantId, "ProductStatusChanged", e, origin,
          new ProductStatusChangedV1(e.productId(), e.storeId(), e.transition().name(), e.from().name(),
              e.to().name(),
              legacyReason(e.reason()), e.reasonCodes(), e.to().isVisible(), e.note(),
              labels(e.reasons())));
      case ProductEvent.ProductBlocked e -> product(tenantId, "ProductBlocked", e, origin,
          new ProductBlockedV1(e.productId(), e.storeId(), legacyReason(e.reason()), e.reasonCodes(),
              e.note(), labels(e.reasons())));
    }).toList();
  }

  /**
   * Converte as mudanças de preço em mensagens do tópico de ofertas; a chave é o
   * id do SKU.
   */
  public List<OutboxMessage> fromOffer(UUID tenantId, List<OfferEvent> events, EventOrigin origin) {
    return events.stream().map(event -> switch (event) {
      case PriceChanged(var skuId, var productId, var storeId, var price, var promotions, var priceVersion, var at) ->
        message(config.offerTopic(), tenantId, "PriceChanged", new Aggregate("offer", skuId), at, origin,
            new PriceChangedV1(skuId, productId, storeId, price.currency(), amount(price.listPrice()),
                amount(price.salePrice()), priceVersion, promotions.stream()
                    .map(p -> new PromotionV1(p.id(), amount(p.salePrice()), p.startsAt(), p.endsAt()))
                    .toList()));
    }).toList();
  }

  /**
   * Converte os eventos de saldo e de reserva em mensagens do tópico de estoque;
   * a chave é o id
   * do SKU, ou da reserva em {@code InventoryReserved}.
   */
  public List<OutboxMessage> fromInventory(UUID tenantId, List<InventoryEvent> events, EventOrigin origin) {
    return events.stream().map(event -> switch (event) {
      case InventoryUpdated(var sku, var cause, var reservationId, var occurredAt) ->
        inventory(tenantId, "InventoryUpdated", new Aggregate("sku", sku.skuId()), occurredAt, origin,
            new InventoryUpdatedV1(stock(sku), cause.name(), reservationId));
      case InventoryReserved(var reservationId, var orderRef, var items, var expiresAt, var occurredAt) ->
        inventory(tenantId, "InventoryReserved", new Aggregate("reservation", reservationId), occurredAt, origin,
            new InventoryReservedV1(reservationId, orderRef, expiresAt,
                items.stream().map(i -> new ReservedItemV1(i.quantity(), stock(i.stock()))).toList()));
      case InventoryDepleted(var sku, var occurredAt) ->
        inventory(tenantId, "InventoryDepleted", new Aggregate("sku", sku.skuId()), occurredAt, origin,
            new InventoryDepletedV1(stock(sku)));
    }).toList();
  }

  /**
   * A situação KYB/KYC da loja mudou no catálogo; a vitrine mostra ou esconde
   * todos os produtos dela.
   */
  public OutboxMessage fromEligibility(UUID tenantId, UUID storeId, SellerStatus previous, SellerStatus status,
      Instant decidedAt,
      Instant occurredAt, EventOrigin origin) {
    return message(config.storeTopic(), tenantId, "StoreEligibilityChanged", new Aggregate("store", storeId),
        occurredAt, origin,
        new StoreEligibilityChangedV1(storeId, previous.name(), status.name(), status.isEligible(),
            decidedAt));
  }

  /** Agregado de onde o evento sai: o tipo e o id, que também é a chave do registro no Kafka. */
  private record Aggregate(String type, UUID id) {
  }

  private OutboxMessage product(UUID tenantId, String type, ProductEvent event, EventOrigin origin, Object data) {
    return message(config.productTopic(), tenantId, type, new Aggregate("product", event.productId()),
        event.occurredAt(), origin,
        data);
  }

  private OutboxMessage inventory(UUID tenantId, String type, Aggregate aggregate, Instant occurredAt,
      EventOrigin origin, Object data) {
    return message(config.inventoryTopic(), tenantId, type, aggregate, occurredAt, origin, data);
  }

  /**
   * WHY: a chave do registro é o id do agregado, para o Kafka manter a ordem dos
   * eventos de um mesmo agregado.
   */
  private OutboxMessage message(String topic, UUID tenantId, String type, Aggregate aggregate, Instant occurredAt,
      EventOrigin origin, Object data) {
    UUID id = UUID.randomUUID();
    Envelope envelope = new Envelope(id, type, EventContracts.VERSION, EventContracts.SOURCE, tenantId,
        aggregate.type(), aggregate.id(), occurredAt, origin.correlationId(), data);
    try {
      return new OutboxMessage(id, tenantId, topic, aggregate.id().toString(), type,
          json.writeValueAsString(envelope), occurredAt,
          origin.traceParent());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Falha ao serializar o evento " + type, e);
    }
  }

  private static List<ReasonLabelV1> labels(List<ReasonLabel> labels) {
    return labels.stream().map(l -> new ReasonLabelV1(l.code(), l.label())).toList();
  }

  /**
   * O texto único em {@code reason}, que o contrato v1 limita a {@link EventContracts#REASON_MAX_LENGTH} caracteres;
   * com vários rótulos e o complemento no limite dele, sai abreviado com reticências, e o conteúdo inteiro segue em
   * {@code reasonLabels} e {@code note}. O corte não separa um par substituto (emoji e outros caracteres fora do
   * BMP), que viraria {@code ?} na gravação em UTF-8.
   */
  static String legacyReason(String text) {
    int max = EventContracts.REASON_MAX_LENGTH;
    if (text == null || text.length() <= max)
      return text;
    int end = max - 1;
    if (Character.isHighSurrogate(text.charAt(end - 1)))
      end--;
    return text.substring(0, end) + "…";
  }

  private static StockV1 stock(InventoryEvent.SkuStock s) {
    return new StockV1(s.skuId(), s.productId(), s.storeId(), s.onHand(), s.reserved(), s.available(), s.version());
  }

  /**
   * Formata o valor como texto decimal exato, sem notação científica; dinheiro
   * ausente vira
   * {@code null}.
   */
  private static String amount(Money money) {
    return money == null ? null : money.amount().toPlainString();
  }
}
