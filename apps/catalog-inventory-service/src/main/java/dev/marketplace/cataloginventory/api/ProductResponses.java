package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.api.ProductModels.ProductBatchResponse;
import dev.marketplace.cataloginventory.api.ProductModels.ProductBatchResult;
import dev.marketplace.cataloginventory.api.ProductModels.ProductPage;
import dev.marketplace.cataloginventory.api.ProductModels.ProductReadings;
import dev.marketplace.cataloginventory.api.ProductModels.ProductResponse;
import dev.marketplace.cataloginventory.api.ProductModels.SkuResponse;
import dev.marketplace.cataloginventory.application.Actor;
import dev.marketplace.cataloginventory.application.Batch;
import dev.marketplace.cataloginventory.application.InventoryService;
import dev.marketplace.cataloginventory.application.OfferService;
import dev.marketplace.cataloginventory.application.Page;
import dev.marketplace.cataloginventory.application.ProductActions;
import dev.marketplace.cataloginventory.application.ProductService;
import dev.marketplace.cataloginventory.domain.inventory.StockLevel;
import dev.marketplace.cataloginventory.domain.offer.Offer;
import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.PublishingIssue;
import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import dev.marketplace.cataloginventory.domain.product.Sku;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Monta as respostas de produto com o saldo e a oferta de cada SKU, a última
 * rejeição do rascunho e as ações do chamador, lidos em lote para todos os
 * produtos da resposta (ADR 014: os agregados continuam separados e a leitura
 * é composta aqui).
 */
@ApplicationScoped
class ProductResponses {
  private static final Logger LOG = Logger.getLogger(ProductResponses.class);
  private final InventoryService inventory;
  private final OfferService offers;
  private final ProductService products;
  private final ProductActions actions;
  private final Clock clock;

  ProductResponses(InventoryService inventory, OfferService offers, ProductService products,
      ProductActions actions, Clock clock) {
    this.inventory = inventory;
    this.offers = offers;
    this.products = products;
    this.actions = actions;
    this.clock = clock;
  }

  /**
   * Responde 200 com o produto lido e o ETag da versão: o saldo e a oferta
   * atuais dos SKUs, as ações de {@code actor} e as pendências de publicação.
   * A falha de qualquer leitura vira o erro dela, como o 503 no prazo.
   */
  Uni<RestResponse<ProductResponse>> ok(Actor actor, Product product) {
    List<Product> items = List.of(product);
    return withActions(actor, items, true, stock(items))
        .map(readings -> ETags.ok(ProductResponse.of(product, readings), product.version()));
  }

  /**
   * Produto recém-gravado com as leituras de {@link #ok}. Sem o saldo, sai o
   * estado gravado; sem outra leitura, sai sem ações, pendências, ofertas e
   * rejeição, e o cliente relê o produto.
   */
  Uni<ProductResponse> saved(Actor actor, Product product) {
    List<Product> items = List.of(product);
    String written = "o produto " + product.id();
    // WHY: a gravação já valeu, com os eventos na outbox; um 503 aqui faria o cliente repetir contra a versão nova.
    return withActions(actor, items, true, stockAfterWrite(items, written))
        .onFailure().recoverWithItem(failure -> {
          LOG.warnf(failure, "Leituras depois de gravar %s falharam; a resposta sai sem ações nem leituras",
              written);
          return new ProductReadings(Optional.empty(), Map.of(), Map.of(), clock.instant(), Map.of(),
              Map.of());
        })
        .map(readings -> ProductResponse.of(product, readings));
  }

  /** Responde 200 com o produto recém-gravado, como em {@link #saved}, e o ETag da versão. */
  Uni<RestResponse<ProductResponse>> savedOk(Actor actor, Product product) {
    return saved(actor, product).map(body -> ETags.ok(body, product.version()));
  }

  /** Responde 200 com o SKU, o saldo e a oferta dele e o ETag da versão do produto. */
  Uni<RestResponse<SkuResponse>> sku(Product product, UUID skuId) {
    List<Product> items = List.of(product);
    return Uni.createFrom().item(() -> product.sku(skuId))
        .flatMap(sku -> readings(items, stock(items))
            .map(readings -> ETags.ok(SkuResponse.of(sku, readings), product.version())));
  }

  /**
   * Página de produtos com o saldo e a oferta dos SKUs, as ações de
   * {@code actor} e o cursor da próxima página; as pendências de publicação
   * ficam para a leitura de um produto.
   */
  Uni<ProductPage> page(Actor actor, Page<Product> page) {
    return withActions(actor, page.items(), false, stock(page.items()))
        .map(readings -> new ProductPage(
            page.items().stream().map(p -> ProductResponse.of(p, readings)).toList(),
            Cursors.next(page)));
  }

  /**
   * Resultado do lote na ordem do pedido: o estado efetivo dos gravados, com
   * o saldo lido numa consulta para todos, e o problema dos que falharam,
   * como o endpoint de um item responderia em {@code path}. Se o saldo não
   * puder ser lido, os gravados saem com o estado gravado.
   */
  Uni<ProductBatchResponse> batch(List<Batch.Outcome<Product>> outcomes, String path) {
    List<Product> saved = outcomes.stream().flatMap(o -> o.value().stream()).toList();
    return stockAfterWrite(saved, "o lote de " + path)
        .map(stock -> new ProductBatchResponse(outcomes.stream()
            .map(o -> o.value()
                .map(p -> ProductBatchResult.succeeded(p, ProductModels.shownStatus(p, stock)))
                .orElseGet(() -> ProductBatchResult.failed(o.id(),
                    ProblemMappers.itemProblem(o.failure().orElseThrow(), path))))
            .toList()));
  }

  private Uni<Optional<Map<UUID, StockLevel>>> stock(List<Product> items) {
    return inventory.stockOf(items).map(Optional::of);
  }

  /**
   * Saldo lido depois de gravar {@code saved}; a falha vira aviso no log e o
   * vazio, com que a resposta mostra o estado gravado.
   */
  private Uni<Optional<Map<UUID, StockLevel>>> stockAfterWrite(List<Product> saved, String written) {
    // WHY: a gravação já valeu, com os eventos na outbox; a falha desta leitura não pode virar 503, que o cliente
    // repetiria contra a versão nova ou, no lote, veria falhar nos itens que deram certo.
    return stock(saved).onFailure().recoverWithItem(failure -> {
      LOG.warnf(failure, "Saldo não lido depois de gravar %s; a resposta sai com o estado gravado", written);
      return Optional.empty();
    });
  }

  /**
   * Leituras de {@link #readings} mais a situação das lojas que as ações pedem,
   * numa consulta para todas, e, com {@code withIssues}, as pendências de
   * publicação de cada produto, que leem a categoria dele.
   */
  private Uni<ProductReadings> withActions(Actor actor, List<Product> items, boolean withIssues,
      Uni<Optional<Map<UUID, StockLevel>>> stock) {
    return Uni.combine().all().unis(readings(items, stock), actions.eligibility(actor, items)).asTuple()
        .flatMap(read -> issues(withIssues ? items : List.of(), read.getItem1().offers())
            .map(issues -> read.getItem1().with(actions(actor, items, read.getItem2(), issues), issues)));
  }

  private static Map<UUID, ProductActions.Available> actions(Actor actor, List<Product> items,
      Map<UUID, SellerStatus> eligibility, Map<UUID, List<PublishingIssue>> issues) {
    return items.stream().collect(Collectors.toMap(Product::id, p -> ProductActions.of(actor, p, eligibility,
        Optional.ofNullable(issues.get(p.id()))), (first, second) -> first));
  }

  /** Pendências de cada produto em status editável, com as ofertas já lidas; os demais ficam fora do mapa. */
  private Uni<Map<UUID, List<PublishingIssue>>> issues(List<Product> items, Map<UUID, Offer> offered) {
    if (items.isEmpty())
      return Uni.createFrom().item(Map.of());
    return Uni.join().all(items.stream().map(p -> products.publishingIssues(p, offeredSkus(p, offered))
        .map(found -> found.map(list -> Map.entry(p.id(), list)))).toList()).andFailFast()
        .map(found -> found.stream().flatMap(Optional::stream)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
  }

  private static Set<UUID> offeredSkus(Product product, Map<UUID, Offer> offered) {
    return product.skus().stream().map(Sku::id).filter(offered::containsKey).collect(Collectors.toSet());
  }

  /**
   * Saldos, ofertas e rejeições dos produtos, uma consulta de cada para todos;
   * as três correm juntas, e produto sem SKU nem rascunho não consulta nada.
   */
  private Uni<ProductReadings> readings(List<Product> items, Uni<Optional<Map<UUID, StockLevel>>> stock) {
    return Uni.combine().all()
        .unis(stock, offers.offersOf(items), products.lastRejections(items)).asTuple()
        .map(read -> new ProductReadings(read.getItem1(), read.getItem2(), read.getItem3(), clock.instant(),
            Map.of(), Map.of()));
  }
}
