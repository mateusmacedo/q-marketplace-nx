package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.application.CategoryService.CategoryView;
import dev.marketplace.cataloginventory.domain.offer.Offer;
import dev.marketplace.cataloginventory.domain.product.CategoryChange;
import dev.marketplace.cataloginventory.domain.product.Dimensions;
import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.ProductDefinition;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import dev.marketplace.cataloginventory.domain.product.ProductTransition;
import dev.marketplace.cataloginventory.domain.product.PublishingIssue;
import dev.marketplace.cataloginventory.domain.product.Sku;
import dev.marketplace.cataloginventory.domain.product.StatusChange;
import io.smallrye.mutiny.Uni;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import static dev.marketplace.cataloginventory.application.ProductSupport.sync;

/**
 * Casos de uso do seller sobre o conteúdo, os SKUs e a publicação dos produtos
 * da própria loja.
 */
public final class ProductService {
  public record NewSku(String sellerSku, Map<String, String> variantAttributes, boolean physical,
      Dimensions dimensions) {
  }

  /** Pendências do rascunho e, quando a categoria muda, o efeito da troca. */
  public record ProductCheck(List<PublishingIssue> publishingIssues, Optional<CategoryChange> categoryChange) {
    public ProductCheck {
      publishingIssues = List.copyOf(publishingIssues);
    }
  }

  private final ProductSupport support;

  public ProductService(ProductRepository products, CategoryRepository categories,
      SellerEligibilityRepository sellers,
      OfferRepository offers, Clock clock) {
    this.support = new ProductSupport(products, categories, sellers, offers, clock);
  }

  /**
   * Cria o produto em rascunho na loja do seller, gravando histórico e evento
   * na outbox; sem idempotência. Exige {@code MANAGE_OWN_PRODUCTS}; categoria
   * inexistente é 404, inativa {@code category-inactive}; {@code sellerSku}
   * já usado na loja, {@link DuplicateException}.
   */
  public Uni<Product> create(Actor actor, UUID categoryId, String currency, String title, String description,
      Map<String, String> attributes, List<NewSku> skus) {
    return Uni.createFrom().deferred(() -> {
      UUID store = ProductAccess.requireSeller(actor);
      return support.category(actor.tenantId(), categoryId).flatMap(view -> {
        view.requireActive();
        ProductDefinition definition = new ProductDefinition(currency, title, description, attributes,
            skus.stream().map(ProductService::toSku).toList());
        Product product = Product.draft(UUID.randomUUID(), store, view.category(), view.effectiveAttributes(),
            definition, actor.subject(), support.now());
        return support.products().insert(product).replaceWith(product);
      });
    });
  }

  /**
   * Busca o produto sob a visibilidade do chamador: leitor do catálogo do
   * tenant ou moderador vê qualquer loja, o seller só a própria. Inexistente
   * ou fora do alcance lança {@link NotFoundException}.
   */
  public Uni<Product> get(Actor actor, UUID id) {
    return support.access.visible(actor, id);
  }

  /**
   * Lista na ordem {@code sort} após o cursor da mesma consulta
   * ({@link ProductQuery}).
   * Quem lê o catálogo do tenant filtra por {@code storeFilter}; o seller vê só a
   * própria loja. Texto longo, cursor de outra consulta ou {@code limit} fora
   * de 1 a 100: {@link InvalidInputException}.
   */
  public Uni<Page<Product>> list(Actor actor, Optional<ProductStatus> status, Optional<UUID> storeFilter,
      Optional<String> text, ProductSort sort, Optional<String> cursorKey, int limit) {
    return Uni.createFrom().deferred(() -> {
      ProductQuery query = ProductQuery.of(ProductAccess.listScope(actor, storeFilter), status, text, sort);
      ProductQuery positioned = query.after(cursorKey);
      return Page.fetch(limit, fetchLimit -> support.products().search(actor.tenantId(), positioned, fetchLimit),
          query::cursorOf);
    });
  }

  /**
   * Última rejeição dos produtos já autorizados para o chamador que estão em
   * {@code DRAFT} porque a moderação os devolveu, por produto, numa consulta
   * para todos; os demais ficam fora do mapa.
   */
  public Uni<Map<UUID, StatusChange>> lastRejections(Collection<Product> products) {
    List<UUID> drafts = products.stream().filter(p -> p.status() == ProductStatus.DRAFT).map(Product::id)
        .toList();
    if (drafts.isEmpty())
      return Uni.createFrom().item(Map.of());
    return support.products().lastChanges(products.iterator().next().tenantId(), drafts)
        .map(changes -> changes.entrySet().stream()
            .filter(e -> e.getValue().transition() == ProductTransition.REJECT)
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)));
  }

  /**
   * Pendências de publicação do produto já autorizado para o chamador, com a
   * categoria atual e os SKUs de {@code offeredSkus} com oferta; vazio onde a
   * edição não vale ({@code BLOCKED} e {@code ARCHIVED}).
   */
  public Uni<Optional<List<PublishingIssue>>> publishingIssues(Product product, Set<UUID> offeredSkus) {
    if (!product.acceptsEdit())
      return Uni.createFrom().item(Optional.empty());
    return support.categoryOf(product).map(view -> Optional
        .of(product.publishingIssues(view.effectiveAttributes(), view.usable(), offeredSkus)));
  }

  /**
   * Avalia o rascunho do conteúdo sem gravar nada: pendências com a categoria
   * e os atributos dele e, se a categoria muda, o efeito da troca. Alcance da
   * edição: só o seller dono. Categoria fora do tenant é 404; estado sem
   * edição, {@code invalid-transition}.
   */
  public Uni<ProductCheck> check(Actor actor, UUID id, UUID categoryId, Map<String, String> attributes) {
    return support.access.owned(actor, id).flatMap(product -> {
      Uni<Optional<CategoryView>> current = product.categoryId().equals(categoryId)
          ? Uni.createFrom().item(Optional.empty())
          : support.categoryOf(product).map(Optional::of);
      Uni<Set<UUID>> offered = support.offers.ofProduct(product.tenantId(), product.id())
          .map(found -> found.stream().map(Offer::skuId).collect(Collectors.toSet()));
      return Uni.combine().all().unis(support.category(actor.tenantId(), categoryId), current, offered)
          .asTuple().map(read -> {
            CategoryView target = read.getItem1();
            List<PublishingIssue> issues = product.draftIssues(attributes, target.effectiveAttributes(),
                target.usable(), read.getItem3());
            return new ProductCheck(issues, read.getItem2().map(view -> product.categoryChange(attributes,
                view.effectiveAttributes(), target.effectiveAttributes())));
          });
    });
  }

  /**
   * Lista as mudanças de estado do produto, da mais antiga para a mais
   * recente, com ator e motivo; visibilidade como em {@link #get}.
   */
  public Uni<List<StatusChange>> history(Actor actor, UUID id) {
    return support.access.visible(actor, id)
        .flatMap(product -> support.products().history(actor.tenantId(), product.id()));
  }

  /**
   * Altera título, descrição, categoria e atributos; produto publicado volta
   * para {@code PENDING_MODERATION}. Recusa {@code BLOCKED}, {@code ARCHIVED}
   * e atributos fora da categoria ({@code invalid-attributes}). Só o seller
   * dono; {@code ifMatch} ausente = sem checagem.
   */
  public Uni<Product> reviseContent(Actor actor, UUID id, Optional<Long> ifMatch, UUID categoryId, String title,
      String description, Map<String, String> attributes) {
    return support.mutateOwned(actor, id, ifMatch, product -> support.category(actor.tenantId(), categoryId)
        .invoke(view -> product.reviseContent(view.requireActive().category(), view.effectiveAttributes(),
            title, description, attributes, actor.subject(), support.now())));
  }

  /**
   * Acrescenta um SKU; produto publicado volta a {@code PENDING_MODERATION}.
   * {@code sellerSku} repetido: {@code duplicate-seller-sku}; fora da
   * categoria: {@code invalid-attributes}. Só o seller dono; {@code ifMatch}
   * ausente = sem checagem.
   */
  public Uni<Product> addSku(Actor actor, UUID id, Optional<Long> ifMatch, NewSku sku) {
    return support.mutateOwned(actor, id, ifMatch, product -> support.categoryOf(product)
        .invoke(view -> product.addSku(toSku(sku), view.effectiveAttributes(), actor.subject(),
            support.now())));
  }

  /**
   * Altera variação, indicador físico e dimensões de um SKU; produto
   * publicado volta para {@code PENDING_MODERATION}. SKU alheio:
   * {@code sku-not-found}; físico sem dimensões: {@code missing-dimensions}.
   * Só o seller dono; {@code ifMatch} ausente = sem checagem.
   */
  public Uni<Product> reviseSku(Actor actor, UUID id, UUID skuId, Optional<Long> ifMatch,
      Map<String, String> variantAttributes,
      boolean physical, Dimensions dimensions) {
    return support.mutateOwned(actor, id, ifMatch, product -> support.categoryOf(product)
        .invoke(view -> product.reviseSku(skuId, variantAttributes, physical, dimensions,
            view.effectiveAttributes(), actor.subject(), support.now())));
  }

  /** A oferta do SKU removido sai junto, porque pertence ao SKU. */
  public Uni<Product> removeSku(Actor actor, UUID id, UUID skuId, Optional<Long> ifMatch) {
    return support.mutateOwned(actor, id, ifMatch, sync(product -> product.removeSku(skuId, support.now())));
  }

  /**
   * Envia o rascunho para {@code PENDING_MODERATION}. Exige produto completo
   * (categoria ativa, oferta nos SKU, atributos obrigatórios), senão
   * {@code product-not-publishable} com os motivos em {@code reasons}. Só o
   * seller dono; {@code ifMatch} ausente = sem checagem.
   */
  public Uni<Product> submit(Actor actor, UUID id, Optional<Long> ifMatch) {
    return support.mutateOwned(actor, id, ifMatch, product -> support.publicationOf(product)
        .invoke(publication -> product.submit(publication.category().effectiveAttributes(),
            publication.category().usable(), publication.offeredSkus(), actor.subject(),
            support.now())));
  }

  /**
   * Pausa a venda ({@code ACTIVE}, esgotado ou não, para
   * {@code PAUSED_BY_SELLER}), tirando o produto da vitrine; outro estado:
   * {@code invalid-transition}. Só o seller dono; {@code ifMatch} ausente =
   * sem checagem.
   */
  public Uni<Product> pause(Actor actor, UUID id, Optional<Long> ifMatch) {
    return support.mutateOwned(actor, id, ifMatch, sync(product -> product.pause(actor.subject(), support.now())));
  }

  /**
   * Retoma o produto pausado ({@code PAUSED_BY_SELLER} para {@code ACTIVE})
   * sem nova moderação, se o seller estiver {@code APPROVED}
   * ({@code seller-not-eligible}). Só o seller dono; {@code ifMatch} ausente
   * = sem checagem.
   */
  public Uni<Product> resume(Actor actor, UUID id, Optional<Long> ifMatch) {
    return support.mutateOwned(actor, id, ifMatch, product -> support.sellerOf(product)
        .invoke(seller -> product.resume(seller, actor.subject(), support.now())));
  }

  /**
   * Arquiva o produto (exclusão lógica) de qualquer estado, menos arquivado;
   * é definitivo e congela edição, oferta e estoque. Só o seller dono;
   * {@code ifMatch} ausente = sem checagem.
   */
  public Uni<Product> archive(Actor actor, UUID id, Optional<Long> ifMatch) {
    return support.mutateOwned(actor, id, ifMatch,
        sync(product -> product.archive(actor.subject(), support.now())));
  }

  /**
   * Aplica {@code action} a cada produto como o endpoint de um item, com as
   * mesmas regras, versão e eventos. Exige seller com loja
   * ({@link AccessDeniedException}); itens fora de 1 a
   * {@link Batch#MAX_ITEMS} ou com id repetido: {@link InvalidInputException}.
   */
  public Uni<List<Batch.Outcome<Product>>> batch(Actor actor, ProductBatchAction action, List<Batch.Item> items) {
    return Uni.createFrom().deferred(() -> {
      ProductAccess.requireSeller(actor);
      Batch.requireItems(items);
      return Batch.run(items, (id, version) -> switch (action) {
        case SUBMIT -> submit(actor, id, version);
        case PAUSE -> pause(actor, id, version);
        case RESUME -> resume(actor, id, version);
        case ARCHIVE -> archive(actor, id, version);
      });
    });
  }

  private static Sku toSku(NewSku sku) {
    return Sku.create(UUID.randomUUID(), sku.sellerSku(), sku.variantAttributes(), sku.physical(),
        sku.dimensions());
  }
}
