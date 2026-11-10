package dev.marketplace.cataloginventory.domain.product;

import dev.marketplace.cataloginventory.domain.Attributes;
import dev.marketplace.cataloginventory.domain.DomainReasons;
import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.InvalidTransitionException;
import dev.marketplace.cataloginventory.domain.Money;
import dev.marketplace.cataloginventory.domain.Reason;
import dev.marketplace.cataloginventory.domain.category.AttributeDefinition;
import dev.marketplace.cataloginventory.domain.category.Category;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static dev.marketplace.cataloginventory.domain.DomainRuleViolation.invalid;
import static dev.marketplace.cataloginventory.domain.DomainRuleViolation.requireText;
import static dev.marketplace.cataloginventory.domain.product.ProductStatus.*;

/**
 * Produto de uma loja: conteúdo, SKUs (variações) e ciclo de publicação. Preço
 * e promoções ficam na oferta de cada SKU, e
 * o saldo, no estoque. Mudança de conteúdo em produto publicado volta para
 * moderação. {@code currency} é a moeda de todas
 * as ofertas do produto; {@code version} sustenta o controle otimista de
 * concorrência.
 */
public final class Product {
  public static final int TITLE_MAX_LENGTH = 200;
  public static final int DESCRIPTION_MAX_LENGTH = 5000;
  public static final int MAX_SKUS = 100;
  private final UUID id;
  private final UUID tenantId;
  private final UUID storeId;
  private final String currency;
  private final Instant createdAt;
  private UUID categoryId;
  private String title;
  private String description;
  private Map<String, String> attributes;
  private final List<Sku> skus;
  private ProductStatus status;
  private long version;
  private Instant updatedAt;
  // WHY: a fila de moderação sai em ordem de chegada, e o updatedAt muda a cada edição feita na fila.
  private Instant moderationQueuedAt;
  private final List<StatusChange> newChanges = new ArrayList<>();
  private final List<ProductEvent> newEvents = new ArrayList<>();

  /**
   * Ponto único de criação: exige identificadores, estado e datas e valida
   * moeda, texto, atributos e conjunto de SKUs. Recusa com
   * {@code invalid-value} ou {@code duplicate-seller-sku}.
   */
  private Product(UUID id, UUID tenantId, UUID storeId, UUID categoryId, String currency, String title,
      String description,
      Map<String, String> attributes, List<Sku> skus, ProductStatus status, long version,
      Instant createdAt, Instant updatedAt, Instant moderationQueuedAt) {
    this.id = Objects.requireNonNull(id, "id obrigatório");
    this.tenantId = Objects.requireNonNull(tenantId, "tenant obrigatório");
    this.storeId = Objects.requireNonNull(storeId, "loja obrigatória");
    this.currency = Money.requireCurrency(currency);
    this.categoryId = Objects.requireNonNull(categoryId, "categoria obrigatória");
    setText(title, description);
    this.attributes = Attributes.copy(attributes);
    this.skus = new ArrayList<>(skus);
    requireSkuSet(this.skus);
    this.status = Objects.requireNonNull(status, "estado obrigatório");
    this.version = version;
    this.createdAt = Objects.requireNonNull(createdAt, "data de criação obrigatória");
    this.updatedAt = Objects.requireNonNull(updatedAt, "data de atualização obrigatória");
    this.moderationQueuedAt = moderationQueuedAt;
  }

  /**
   * Cria o produto em {@code DRAFT} no tenant da categoria, registrando a
   * transição {@code CREATE} e o evento {@code ProductCreated}. Recusa com
   * {@code category-inactive}, {@code invalid-attributes},
   * {@code duplicate-seller-sku} ou {@code invalid-value}.
   */
  public static Product draft(UUID id, UUID storeId, Category category, List<AttributeDefinition> effective,
      String currency,
      String title, String description, Map<String, String> attributes, List<Sku> skus, String actor,
      Instant now) {
    requireUsable(category);
    Product product = new Product(id, category.tenantId(), storeId, category.id(), currency, title, description,
        attributes,
        skus, DRAFT, 0, now, now, null);
    requireValidValues(product.attributes, product.skus, effective);
    product.record(ProductTransition.CREATE, actor, null, now);
    product.newEvents.add(new ProductEvent.ProductCreated(id, storeId, product.categoryId, product.currency,
        product.title,
        product.skus.stream().map(s -> new ProductEvent.SkuSummary(s.id(), s.sellerSku())).toList(),
        now));
    return product;
  }

  /**
   * Reconstrói o produto persistido preservando estado e versão, sem
   * registrar transições nem eventos. Reaplica só as validações estruturais
   * (moeda, texto e SKUs), não os valores de atributos contra a categoria.
   * {@code moderationQueuedAt} é nulo fora de {@code PENDING_MODERATION}.
   */
  public static Product restore(UUID id, UUID tenantId, UUID storeId, UUID categoryId, String currency, String title,
      String description, Map<String, String> attributes, List<Sku> skus, ProductStatus status,
      long version, Instant createdAt, Instant updatedAt, Instant moderationQueuedAt) {
    return new Product(id, tenantId, storeId, categoryId, currency, title, description, attributes, skus, status,
        version,
        createdAt, updatedAt, moderationQueuedAt);
  }

  /**
   * Substitui título, descrição, categoria e atributos; se publicado, volta
   * para {@code PENDING_MODERATION}. Recusa com {@code invalid-transition}
   * (bloqueado ou arquivado), {@code category-inactive},
   * {@code invalid-attributes} e {@code invalid-value}.
   */
  public void reviseContent(Category category, List<AttributeDefinition> effective, String title, String description,
      Map<String, String> attributes, String actor, Instant now) {
    requireEditable();
    requireUsable(category);
    if (!category.tenantId().equals(tenantId))
      throw invalid("Categoria de outro tenant");
    Map<String, String> revised = Attributes.copy(attributes);
    requireValidValues(revised, skus, effective);
    setText(title, description);
    this.categoryId = category.id();
    this.attributes = revised;
    reviewIfPublished(actor, now);
  }

  /**
   * Acrescenta o SKU; em produto publicado, volta para
   * {@code PENDING_MODERATION}. Recusa com {@code invalid-transition}
   * (bloqueado ou arquivado), {@code duplicate-seller-sku},
   * {@code invalid-attributes} e {@code invalid-value}.
   */
  public void addSku(Sku sku, List<AttributeDefinition> effective, String actor, Instant now) {
    requireEditable();
    List<Sku> next = new ArrayList<>(skus);
    next.add(sku);
    requireSkuSet(next);
    requireValidValues(Map.of(), List.of(sku), effective);
    skus.add(sku);
    reviewIfPublished(actor, now);
  }

  /**
   * Redefine variante, tipo físico e dimensões do SKU; em produto publicado,
   * volta para {@code PENDING_MODERATION}. Recusa com {@code sku-not-found},
   * {@code invalid-transition}, {@code invalid-attributes},
   * {@code missing-dimensions} e {@code invalid-value}.
   */
  public void reviseSku(UUID skuId, Map<String, String> variantAttributes, boolean physical, Dimensions dimensions,
      List<AttributeDefinition> effective, String actor, Instant now) {
    requireEditable();
    Sku sku = sku(skuId);
    Sku candidate = Sku.create(UUID.randomUUID(), sku.sellerSku(), variantAttributes, physical, dimensions);
    requireValidValues(Map.of(), List.of(candidate), effective);
    sku.define(variantAttributes, physical, dimensions);
    reviewIfPublished(actor, now);
  }

  /**
   * Remove o SKU de produto em rascunho, sem registrar transição. Recusa com
   * {@code sku-removal-requires-draft} fora de {@code DRAFT} e com
   * {@code sku-not-found} se o SKU não pertencer ao produto.
   */
  public void removeSku(UUID skuId, Instant now) {
    skuRemovalRefusal().ifPresent(reason -> {
      throw new DomainRuleViolation(reason,
          "SKU só pode ser removido de produto em rascunho; pause ou arquive o produto");
    });
    skus.remove(sku(skuId));
    touch(now);
  }

  /** Oferta e estoque de um produto arquivado ficam congelados. */
  public void requireNotArchived(String operation) {
    if (!acceptsOfferAndStockChanges())
      throw new InvalidTransitionException(operation, status.name());
  }

  /** Conteúdo e SKUs aceitam edição: todo estado, menos bloqueado e arquivado. */
  public boolean acceptsEdit() {
    return status.isEditable();
  }

  /** Oferta e estoque aceitam mudança: todo estado, menos arquivado. */
  public boolean acceptsOfferAndStockChanges() {
    return status != ARCHIVED;
  }

  /** Salvar conteúdo ou SKU devolve o produto à moderação: só nos estados publicados. */
  public boolean editReturnsToModeration() {
    return ProductTransition.REVIEW_REQUIRED.acceptsFrom(status);
  }

  /**
   * O que impede um SKU novo além do estado: o limite de {@link #MAX_SKUS};
   * vazio quando cabe mais um.
   */
  public Optional<Reason> skuAdditionRefusal() {
    return skus.size() >= MAX_SKUS ? Optional.of(skuLimit()) : Optional.empty();
  }

  /** O que impede remover SKU: fora de {@code DRAFT}; vazio no rascunho. */
  public Optional<Reason> skuRemovalRefusal() {
    return status == DRAFT ? Optional.empty() : Optional.of(Reason.of(DomainReasons.SKU_REMOVAL_REQUIRES_DRAFT));
  }

  /**
   * O que impede ativar produto da loja na situação {@code seller}, na
   * aprovação e na retomada; vazio quando ela está {@code APPROVED}.
   */
  public static Optional<Reason> ineligibility(SellerStatus seller) {
    return seller.isEligible()
        ? Optional.empty()
        : Optional.of(Reason.of(DomainReasons.SELLER_NOT_ELIGIBLE, seller));
  }

  /**
   * {@code offeredSkus}: SKUs que já têm oferta; todo SKU precisa de uma para o
   * produto ser publicado.
   */
  public List<Reason> publishingViolations(List<AttributeDefinition> effective, boolean categoryActive,
      Set<UUID> offeredSkus) {
    return PublishingPolicy.publishingViolations(attributes, skus, effective, categoryActive, offeredSkus);
  }

  /** Como {@link #publishingViolations}, com o campo onde cada pendência se resolve. */
  public List<PublishingIssue> publishingIssues(List<AttributeDefinition> effective, boolean categoryActive,
      Set<UUID> offeredSkus) {
    return PublishingPolicy.publishingIssues(attributes, skus, effective, categoryActive, offeredSkus);
  }

  /**
   * Pendências que o produto teria com os {@code attributes} do rascunho na
   * categoria de {@code effective}, sem alterar nada; os SKUs são os gravados.
   * Recusa com {@code invalid-transition} onde a edição não vale.
   */
  public List<PublishingIssue> draftIssues(Map<String, String> attributes, List<AttributeDefinition> effective,
      boolean categoryActive, Set<UUID> offeredSkus) {
    requireEditable();
    return PublishingPolicy.publishingIssues(Attributes.copy(attributes), skus, effective, categoryActive,
        offeredSkus);
  }

  /**
   * Efeito de trocar a categoria de {@code current} para {@code target} com os
   * {@code attributes} do rascunho e os SKUs gravados, sem alterar nada.
   */
  public CategoryChange categoryChange(Map<String, String> attributes, List<AttributeDefinition> current,
      List<AttributeDefinition> target) {
    return CategoryChange.of(Attributes.copy(attributes), skus, current, target);
  }

  /**
   * Envia o rascunho para moderação ({@code DRAFT} para
   * {@code PENDING_MODERATION}). Recusa com {@code invalid-transition} fora
   * de {@code DRAFT} e com {@code product-not-publishable} se houver
   * pendências em {@code reasons}.
   */
  public void submit(List<AttributeDefinition> effective, boolean categoryActive, Set<UUID> offeredSkus, String actor,
      Instant now) {
    requireFrom(ProductTransition.SUBMIT);
    requirePublishable(effective, categoryActive, offeredSkus);
    record(ProductTransition.SUBMIT, actor, null, now);
  }

  /**
   * Aprova o produto em moderação ({@code PENDING_MODERATION} para
   * {@code ACTIVE}). Recusa com {@code invalid-transition} fora desse estado,
   * {@code product-not-publishable} se houver pendências e
   * {@code seller-not-eligible} se o seller não estiver {@code APPROVED}.
   */
  public void approve(List<AttributeDefinition> effective, boolean categoryActive, Set<UUID> offeredSkus,
      SellerStatus seller,
      String actor, Instant now) {
    requireFrom(ProductTransition.APPROVE);
    requirePublishable(effective, categoryActive, offeredSkus);
    requireEligible(seller);
    record(ProductTransition.APPROVE, actor, null, now);
  }

  /**
   * Devolve o produto em moderação para {@code DRAFT}, guardando os motivos e o
   * complemento, já validados em {@link DecisionReason#of}. Recusa com
   * {@code invalid-transition} fora de {@code PENDING_MODERATION}.
   */
  public void reject(DecisionReason reason, String actor, Instant now) {
    requireFrom(ProductTransition.REJECT);
    record(ProductTransition.REJECT, actor, requireDecision(reason, ProductTransition.REJECT), now);
  }

  /**
   * Bloqueia o produto, guardando o motivo e registrando
   * {@code ProductStatusChanged} e {@code ProductBlocked}. Recusa com
   * {@code invalid-transition} se já bloqueado ou arquivado.
   */
  public void block(DecisionReason reason, String actor, Instant now) {
    requireFrom(ProductTransition.BLOCK);
    record(ProductTransition.BLOCK, actor, requireDecision(reason, ProductTransition.BLOCK), now);
    newEvents.add(new ProductEvent.ProductBlocked(id, storeId, reason.reasons(), reason.note(), now));
  }

  /**
   * Desbloqueia o produto, que volta para {@code PENDING_MODERATION}, não
   * direto para {@code ACTIVE}. Recusa com {@code invalid-transition} se não
   * estiver {@code BLOCKED}.
   */
  public void unblock(String actor, Instant now) {
    requireFrom(ProductTransition.UNBLOCK);
    record(ProductTransition.UNBLOCK, actor, null, now);
  }

  /**
   * Pausa a venda a pedido do seller ({@code ACTIVE}, esgotado ou não, para
   * {@code PAUSED_BY_SELLER}). Recusa com {@code invalid-transition} fora de
   * {@code ACTIVE}.
   */
  public void pause(String actor, Instant now) {
    requireFrom(ProductTransition.PAUSE);
    record(ProductTransition.PAUSE, actor, null, now);
  }

  /**
   * Retoma o produto pausado para {@code ACTIVE}. Recusa com
   * {@code invalid-transition} fora de {@code PAUSED_BY_SELLER} e com
   * {@code seller-not-eligible} se o seller não estiver {@code APPROVED}.
   */
  public void resume(SellerStatus seller, String actor, Instant now) {
    requireFrom(ProductTransition.RESUME);
    requireEligible(seller);
    record(ProductTransition.RESUME, actor, null, now);
  }

  /**
   * Arquiva o produto (exclusão lógica) a partir de qualquer estado, exceto
   * {@code ARCHIVED}. Recusa com {@code invalid-transition} se já estiver
   * arquivado.
   */
  public void archive(String actor, Instant now) {
    requireFrom(ProductTransition.ARCHIVE);
    record(ProductTransition.ARCHIVE, actor, null, now);
  }

  /**
   * WHY: esgotado é derivado do saldo na leitura (ADR 012); gravá-lo a cada venda
   * geraria disputa no agregado.
   */
  public ProductStatus effectiveStatus(boolean anyAvailable) {
    return status == ACTIVE && !anyAvailable ? OUT_OF_STOCK : status;
  }

  /**
   * Autoridade de visibilidade pública: estado visível e seller aprovado,
   * independentemente do índice de busca.
   */
  public boolean isPubliclyVisible(SellerStatus seller) {
    return status.isVisible() && seller.isEligible();
  }

  /**
   * Busca o SKU do produto; recusa com {@code sku-not-found} se ele não
   * pertencer ao produto.
   */
  public Sku sku(UUID skuId) {
    return skus.stream().filter(s -> s.id().equals(skuId)).findFirst()
        .orElseThrow(() -> new DomainRuleViolation(DomainReasons.SKU_NOT_FOUND, "SKU não pertence ao produto"));
  }

  /**
   * Transições ainda não persistidas; o repositório as grava na mesma transação
   * do agregado.
   */
  public List<StatusChange> drainChanges() {
    List<StatusChange> drained = List.copyOf(newChanges);
    newChanges.clear();
    return drained;
  }

  /**
   * Eventos ainda não persistidos; o repositório os grava na outbox, na mesma
   * transação do agregado.
   */
  public List<ProductEvent> drainEvents() {
    List<ProductEvent> drained = List.copyOf(newEvents);
    newEvents.clear();
    return drained;
  }

  /** Adota a versão gravada pelo repositório após persistir o produto. */
  public void markPersisted(long newVersion) {
    this.version = newVersion;
  }

  public UUID id() {
    return id;
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

  public UUID categoryId() {
    return categoryId;
  }

  public String title() {
    return title;
  }

  public String description() {
    return description;
  }

  public Map<String, String> attributes() {
    return attributes;
  }

  public List<Sku> skus() {
    return List.copyOf(skus);
  }

  public ProductStatus status() {
    return status;
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
   * Quando o produto entrou em {@code PENDING_MODERATION} (envio,
   * desbloqueio ou volta à moderação); vazio nos demais estados.
   */
  public Optional<Instant> moderationQueuedAt() {
    return Optional.ofNullable(moderationQueuedAt);
  }

  /**
   * Em produto publicado, registra {@code REVIEW_REQUIRED} e o devolve para
   * {@code PENDING_MODERATION}; nos demais só atualiza {@code updatedAt}.
   */
  private void reviewIfPublished(String actor, Instant now) {
    if (editReturnsToModeration())
      record(ProductTransition.REVIEW_REQUIRED, actor, null, now);
    else
      touch(now);
  }

  /**
   * Registra o {@code StatusChange} e, exceto na criação, o evento
   * {@code ProductStatusChanged}; leva o produto ao destino da transição,
   * marca a entrada na fila de moderação e atualiza {@code updatedAt}.
   */
  private void record(ProductTransition transition, String actor, DecisionReason reason, Instant now) {
    ProductStatus from = status;
    ProductStatus to = transition.to();
    List<ReasonLabel> reasons = reason == null ? List.of() : reason.reasons();
    String note = reason == null ? null : reason.note();
    newChanges.add(new StatusChange(UUID.randomUUID(), transition, from, to,
        Objects.requireNonNull(actor, "ator obrigatório"),
        reasons, note, now));
    if (transition != ProductTransition.CREATE)
      newEvents.add(new ProductEvent.ProductStatusChanged(id, storeId, transition, from, to, reasons, note, now));
    this.status = to;
    this.moderationQueuedAt = to == PENDING_MODERATION ? now : null;
    touch(now);
  }

  private void touch(Instant now) {
    this.updatedAt = now;
  }

  private void requireFrom(ProductTransition transition) {
    if (!transition.acceptsFrom(status))
      throw new InvalidTransitionException(transition.name(), status.name());
  }

  /**
   * Recusa com {@code invalid-transition}, na operação {@code EDIT}, se o
   * estado não aceitar edição.
   */
  private void requireEditable() {
    if (!acceptsEdit())
      throw new InvalidTransitionException("EDIT", status.name());
  }

  private void requirePublishable(List<AttributeDefinition> effective, boolean categoryActive,
      Set<UUID> offeredSkus) {
    List<Reason> reasons = publishingViolations(effective, categoryActive, offeredSkus);
    if (!reasons.isEmpty())
      throw new DomainRuleViolation(DomainReasons.PRODUCT_NOT_PUBLISHABLE, "Produto incompleto para publicação",
          reasons);
  }

  private static void requireEligible(SellerStatus seller) {
    ineligibility(seller).ifPresent(reason -> {
      throw new DomainRuleViolation(DomainReasons.SELLER_NOT_ELIGIBLE,
          "Somente lojas com KYC aprovado podem ter produtos ativos", List.of(reason));
    });
  }

  /**
   * Recusa com {@code invalid-attributes}, trazendo os motivos, se os valores
   * de atributos do produto e dos SKUs violarem as definições efetivas.
   */
  private static void requireValidValues(Map<String, String> values, List<Sku> candidates,
      List<AttributeDefinition> effective) {
    List<Reason> reasons = PublishingPolicy.valueViolations(values, candidates, effective);
    if (!reasons.isEmpty())
      throw new DomainRuleViolation(DomainReasons.INVALID_ATTRIBUTES, "Atributos inválidos para a categoria",
          reasons);
  }

  private static void requireUsable(Category category) {
    if (!category.active())
      throw new DomainRuleViolation(Reason.of(DomainReasons.CATEGORY_INACTIVE, category.code()),
          "Categoria " + category.code() + " está inativa");
  }

  /** Exige o motivo composto para a mesma decisão; outro é erro de programação. */
  private static DecisionReason requireDecision(DecisionReason reason, ProductTransition decision) {
    Objects.requireNonNull(reason, "motivo obrigatório");
    if (reason.decision() != decision)
      throw new IllegalArgumentException("Motivo de " + reason.decision() + " usado em " + decision);
    return reason;
  }

  /**
   * Valida e atribui título e descrição; descrição nula vira vazia. Recusa
   * com {@code invalid-value} título vazio ou acima de
   * {@link #TITLE_MAX_LENGTH} e descrição acima de
   * {@link #DESCRIPTION_MAX_LENGTH}.
   */
  private void setText(String title, String description) {
    String checkedTitle = requireText(title, TITLE_MAX_LENGTH,
        "Título é obrigatório e tem até " + TITLE_MAX_LENGTH + " caracteres");
    String text = description == null ? "" : description;
    if (text.length() > DESCRIPTION_MAX_LENGTH)
      throw invalid("Descrição tem até " + DESCRIPTION_MAX_LENGTH + " caracteres");
    this.title = checkedTitle;
    this.description = text;
  }

  /**
   * Recusa com {@code invalid-value} mais de {@link #MAX_SKUS} SKUs e com
   * {@code duplicate-seller-sku} código de seller repetido.
   */
  private static void requireSkuSet(List<Sku> candidates) {
    if (candidates.size() > MAX_SKUS)
      throw new DomainRuleViolation(skuLimit(), skuLimit().message());
    Set<String> sellerSkus = new HashSet<>();
    for (Sku s : candidates) {
      if (!sellerSkus.add(s.sellerSku()))
        throw new DomainRuleViolation(Reason.of(DomainReasons.DUPLICATE_SELLER_SKU, s.sellerSku()),
            "SKU " + s.sellerSku() + " repetido no produto");
    }
  }

  private static Reason skuLimit() {
    return Reason.of(DomainReasons.INVALID_VALUE).withMessage("Produto aceita até " + MAX_SKUS + " SKUs");
  }
}
