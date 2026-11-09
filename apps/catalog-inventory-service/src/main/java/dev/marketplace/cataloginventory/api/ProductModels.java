package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.api.BatchModels.BatchItemRequest;
import dev.marketplace.cataloginventory.api.BatchModels.BatchOutcome;
import dev.marketplace.cataloginventory.api.InventoryModels.StockResponse;
import dev.marketplace.cataloginventory.application.Batch;
import dev.marketplace.cataloginventory.application.ProductActions;
import dev.marketplace.cataloginventory.application.ProductBatchAction;
import dev.marketplace.cataloginventory.application.ProductService;
import dev.marketplace.cataloginventory.application.ProductService.NewSku;
import dev.marketplace.cataloginventory.domain.Money;
import dev.marketplace.cataloginventory.domain.inventory.StockLevel;
import dev.marketplace.cataloginventory.domain.offer.EffectivePrice;
import dev.marketplace.cataloginventory.domain.offer.Offer;
import dev.marketplace.cataloginventory.domain.product.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Contratos HTTP de produtos: conteúdo, SKUs e publicação. Preço é gravado em
 * ofertas e saldo em estoque; a resposta do produto os traz só para leitura.
 */
public final class ProductModels {
    private ProductModels() {
    }

    public record DimensionsDto(@NotNull @Positive BigDecimal heightCm, @NotNull @Positive BigDecimal widthCm,
            @NotNull @Positive BigDecimal lengthCm, @NotNull @Positive Integer weightGrams) {
        Dimensions toDomain() {
            return new Dimensions(heightCm, widthCm, lengthCm, weightGrams);
        }

        static DimensionsDto of(Dimensions d) {
            return d == null ? null : new DimensionsDto(d.heightCm(), d.widthCm(), d.lengthCm(), d.weightGrams());
        }

        /** Converte as dimensões recebidas; nulo (SKU sem dimensões) continua nulo. */
        static Dimensions domainOf(DimensionsDto dto) {
            return dto == null ? null : dto.toDomain();
        }
    }

    public record SkuRequest(@NotBlank @Pattern(regexp = Sku.SELLER_SKU_PATTERN) String sellerSku,
            Map<@NotBlank String, @NotNull String> variantAttributes, boolean physical,
            @Schema(description = "Obrigatório para SKU físico", nullable = true) @Valid DimensionsDto dimensions) {
        NewSku toCommand() {
            return new NewSku(sellerSku, variantAttributes, physical, DimensionsDto.domainOf(dimensions));
        }
    }

    public record CreateProductRequest(@NotNull UUID categoryId,
            @Schema(description = "Moeda ISO 4217 de todas as ofertas do produto") @NotBlank @Pattern(regexp = Money.CURRENCY_PATTERN) String currency,
            @NotBlank @Size(max = Product.TITLE_MAX_LENGTH) String title,
            @Size(max = Product.DESCRIPTION_MAX_LENGTH) String description,
            Map<@NotBlank String, @NotNull String> attributes,
            @Size(max = Product.MAX_SKUS) List<@Valid @NotNull SkuRequest> skus) {
    }

    public record ReviseProductRequest(@NotNull UUID categoryId,
            @NotBlank @Size(max = Product.TITLE_MAX_LENGTH) String title,
            @Size(max = Product.DESCRIPTION_MAX_LENGTH) String description,
            Map<@NotBlank String, @NotNull String> attributes) {
    }

    public record ReviseSkuRequest(Map<@NotBlank String, @NotNull String> variantAttributes, boolean physical,
            @Valid DimensionsDto dimensions) {
    }

    @Schema(description = "Ação do seller em lote, com as regras do endpoint de um item")
    public record ProductBatchRequest(@NotNull ProductBatchAction action,
            @NotNull @Size(min = 1, max = Batch.MAX_ITEMS) List<@NotNull @Valid BatchItemRequest> items) {
    }

    @Schema(description = "Desfecho de um produto do lote, na posição em que veio no pedido")
    public record ProductBatchResult(UUID id, BatchOutcome outcome,
            @Schema(description = "Estado novo, como a resposta do produto o mostra, ou o gravado se o saldo não pôde ser lido depois do lote; ausente na falha", nullable = true) ProductStatus status,
            @Schema(description = "Versão nova, para o próximo If-Match; ausente na falha", nullable = true) Long version,
            @Schema(description = "Problema que o endpoint de um item responderia; ausente no sucesso", nullable = true) Problem problem) {
        static ProductBatchResult succeeded(Product p, ProductStatus status) {
            return new ProductBatchResult(p.id(), BatchOutcome.SUCCEEDED, status, p.version(), null);
        }

        static ProductBatchResult failed(UUID id, Problem problem) {
            return new ProductBatchResult(id, BatchOutcome.FAILED, null, null, problem);
        }
    }

    public record ProductBatchResponse(List<ProductBatchResult> results) {
    }

    /**
     * Leituras que acompanham os produtos de uma resposta, feitas em lote:
     * saldo e oferta por SKU, última rejeição, ações do chamador e pendências
     * de publicação por produto, e o instante do preço vigente. {@code stock}
     * fica vazio quando o saldo não pôde ser lido depois de uma gravação.
     */
    record ProductReadings(Optional<Map<UUID, StockLevel>> stock, Map<UUID, Offer> offers,
            Map<UUID, StatusChange> rejections, Instant now, Map<UUID, ProductActions.Available> actions,
            Map<UUID, List<PublishingIssue>> issues) {
        ProductReadings with(Map<UUID, ProductActions.Available> actions, Map<UUID, List<PublishingIssue>> issues) {
            return new ProductReadings(stock, offers, rejections, now, actions, issues);
        }
    }

    @Schema(description = "Ação que vale no status e para o chamador, mas que um motivo impede agora; o cliente a mostra desabilitada, com o motivo")
    public record BlockedActionResponse(@Schema(description = "Código da ação, como em actions") String action,
            @Schema(description = "Motivo, no formato de reasonDetails do corpo de erro") Problem.ReasonDetail reason) {
        static BlockedActionResponse of(ProductActions.Blocked blocked) {
            return new BlockedActionResponse(blocked.action().name(),
                    ReasonMessages.detail(blocked.reason(), "A ação não está disponível agora."));
        }
    }

    @Schema(description = "Pendência de publicação e o campo do produto onde ela se resolve")
    public record PublishingIssueResponse(
            @Schema(description = "Código estável do motivo, sem os parâmetros (missing-offer)") String code,
            @Schema(description = "Valores do caso na ordem do texto, como o SKU e o atributo") List<String> params,
            @Schema(description = "Texto em português para mostrar ao usuário") String message,
            @Schema(description = "Campo do produto onde a pendência se resolve: categoryId, attributes.<código>,"
                    + " skus, skus[<sku>].offer, skus[<sku>].variantAttributes ou"
                    + " skus[<sku>].variantAttributes.<código>") String field) {
        static PublishingIssueResponse of(PublishingIssue issue) {
            Problem.ReasonDetail detail = ReasonMessages.detail(issue.reason(), "Há uma pendência para publicar.");
            return new PublishingIssueResponse(detail.code(), detail.params(), detail.message(), issue.field());
        }

        static List<PublishingIssueResponse> of(List<PublishingIssue> issues) {
            return issues.stream().map(PublishingIssueResponse::of).toList();
        }
    }

    @Schema(description = "Oferta do SKU no instante da leitura; as promoções ficam em GET /offers/{skuId}")
    public record SkuOfferResponse(String currency,
            @Schema(description = "Preço \"de\" (riscado)", nullable = true) MoneyDto listPrice,
            @Schema(description = "Preço \"por\", sem promoção") MoneyDto salePrice,
            @Schema(description = "Preço vigente: o da promoção ativa ou o \"por\"") MoneyDto effectivePrice,
            @Schema(description = "Indica se o preço vigente vem de uma promoção") boolean onPromotion,
            @Schema(description = "Versão da oferta, usada no If-Match") long version) {
        static SkuOfferResponse of(Offer o, Instant now) {
            EffectivePrice effective = o.effectivePrice(now);
            return new SkuOfferResponse(o.currency(), MoneyDto.of(o.price().listPrice()),
                    MoneyDto.of(o.price().salePrice()), MoneyDto.of(effective.salePrice()),
                    effective.promotionId().isPresent(), o.version());
        }
    }

    public record SkuResponse(UUID id, String sellerSku, Map<String, String> variantAttributes, boolean physical,
            DimensionsDto dimensions,
            @Schema(description = "Ausente enquanto o SKU não tem oferta", nullable = true) SkuOfferResponse offer,
            @Schema(description = "Saldo do SKU; ausente se o SKU foi removido entre as leituras ou, na resposta de uma"
                    + " gravação, se o saldo não pôde ser lido depois dela", nullable = true) StockResponse stock) {
        static SkuResponse of(Sku s, ProductReadings readings) {
            Offer offer = readings.offers().get(s.id());
            StockLevel level = readings.stock().map(levels -> levels.get(s.id())).orElse(null);
            return new SkuResponse(s.id(), s.sellerSku(), s.variantAttributes(), s.physical(),
                    DimensionsDto.of(s.dimensions().orElse(null)),
                    offer == null ? null : SkuOfferResponse.of(offer, readings.now()),
                    level == null ? null : StockResponse.of(level));
        }
    }

    @Schema(description = "Motivo pronto marcado na decisão, com o rótulo que tinha nela")
    public record ReasonLabelResponse(@Schema(description = "Código estável do motivo") String code,
            @Schema(description = "Rótulo da época da decisão; renomear o motivo depois não o muda") String label) {
        static List<ReasonLabelResponse> of(List<ReasonLabel> labels) {
            return labels.stream().map(l -> new ReasonLabelResponse(l.code(), l.label())).toList();
        }
    }

    @Schema(description = "Rejeição que devolveu o produto ao rascunho")
    public record RejectionResponse(
            @Schema(description = "Texto único derivado dos rótulos da época e do complemento, para quem só exibe uma linha") String reason,
            @Schema(description = "Códigos dos motivos prontos (GET /moderation/reasons); vazio quando só houve complemento") List<String> reasonCodes,
            @Schema(description = "Motivos prontos com o rótulo da época, na ordem do catálogo de então") List<ReasonLabelResponse> reasonLabels,
            @Schema(description = "Complemento livre do moderador", nullable = true) String note,
            String actor, Instant at) {
        static RejectionResponse of(StatusChange c) {
            return new RejectionResponse(c.reason(), c.reasonCodes(), ReasonLabelResponse.of(c.reasons()), c.note(),
                    c.actor(), c.at());
        }
    }

    public record ProductResponse(UUID id, UUID storeId, UUID categoryId, String currency, String title,
            String description,
            Map<String, String> attributes,
            @Schema(description = "ACTIVE sem saldo disponível aparece como OUT_OF_STOCK; na resposta de uma gravação,"
                    + " se o saldo não pôde ser lido depois dela, sai o estado gravado") ProductStatus status,
            long version, Instant createdAt, Instant updatedAt,
            @Schema(description = "Entrada na fila de moderação, que ordena a fila; presente só em PENDING_MODERATION", nullable = true) Instant moderationQueuedAt,
            List<SkuResponse> skus,
            @Schema(description = "Presente no rascunho cuja última transição foi a rejeição na moderação; ausente também quando actions está ausente", nullable = true) RejectionResponse lastRejection,
            @Schema(description = "Ações que o chamador pode executar agora (EDIT, CHANGE_CATEGORY, ADD_SKU,"
                    + " REMOVE_SKU, EDIT_OFFER, EDIT_STOCK, SUBMIT, PAUSE, RESUME, ARCHIVE, APPROVE, REJECT, BLOCK,"
                    + " UNBLOCK). Lista aberta: o cliente ignora o código que não conhece. Ausente só na resposta de uma"
                    + " gravação cujas leituras posteriores falharam: a gravação valeu, e o cliente relê o produto", nullable = true) List<String> actions,
            @Schema(description = "Ações que valem no status e para o chamador, mas que um motivo impede agora; ausente quando"
                    + " actions está ausente", nullable = true) List<BlockedActionResponse> blockedActions,
            @Schema(description = "Salvar conteúdo ou SKU devolve o produto publicado à moderação; falso quando o chamador não"
                    + " edita ou quando actions está ausente") boolean saveReturnsToModeration,
            @Schema(description = "Pendências de publicação, na ordem da política; presente na leitura de um produto em"
                    + " status editável e ausente na listagem, na fila, nos status BLOCKED e ARCHIVED e quando actions está ausente", nullable = true) List<PublishingIssueResponse> publishingIssues) {
        static ProductResponse of(Product p, ProductReadings readings) {
            StatusChange rejection = readings.rejections().get(p.id());
            ProductActions.Available available = readings.actions().get(p.id());
            List<PublishingIssue> issues = readings.issues().get(p.id());
            return new ProductResponse(p.id(), p.storeId(), p.categoryId(), p.currency(), p.title(), p.description(),
                    p.attributes(),
                    shownStatus(p, readings.stock()), p.version(), p.createdAt(), p.updatedAt(),
                    p.moderationQueuedAt().orElse(null),
                    p.skus().stream().map(s -> SkuResponse.of(s, readings)).toList(),
                    rejection == null ? null : RejectionResponse.of(rejection),
                    available == null ? null : available.allowed().stream().map(Enum::name).toList(),
                    available == null ? null : available.blocked().stream().map(BlockedActionResponse::of).toList(),
                    available != null && available.saveReturnsToModeration(),
                    issues == null ? null : PublishingIssueResponse.of(issues));
        }
    }

    public record ProductPage(List<ProductResponse> items,
            @Schema(description = "Cursor opaco da próxima página; ausente na última", nullable = true) String nextCursor) {
    }

    @Schema(description = "Rascunho do conteúdo ainda não salvo; os SKUs são os gravados")
    public record ProductCheckRequest(@NotNull UUID categoryId, Map<@NotBlank String, @NotNull String> attributes) {
    }

    @Schema(description = "Atributo do produto que a categoria nova não tem ou cujo valor ela não aceita; sai do rascunho na troca")
    public record RemovedAttributeResponse(String code,
            @Schema(description = "Rótulo na categoria atual; o código quando ela também não o tem") String label,
            @Schema(description = "Valor que se perde") String value,
            @Schema(description = "Motivo, no formato de reasonDetails do corpo de erro") Problem.ReasonDetail reason) {
    }

    @Schema(description = "Atributo obrigatório da categoria nova que a atual não exigia; vira pendência e não impede a troca")
    public record NewRequiredAttributeResponse(String code, String label,
            @Schema(description = "Valor pertence a cada SKU") boolean variant) {
    }

    @Schema(description = "SKU cuja variação a categoria nova não tem ou não aceita; impede a troca, que a gravação recusaria com invalid-attributes")
    public record BlockingSkuResponse(String sellerSku,
            @Schema(description = "Motivos, no formato de reasonDetails do corpo de erro") List<Problem.ReasonDetail> reasons) {
    }

    @Schema(description = "Efeito de trocar a categoria, pela regra que a gravação aplica")
    public record CategoryChangeResponse(List<RemovedAttributeResponse> removedAttributes,
            List<NewRequiredAttributeResponse> newRequired, List<BlockingSkuResponse> blockingSkus) {
        static CategoryChangeResponse of(CategoryChange change) {
            return new CategoryChangeResponse(
                    change.removedAttributes().stream().map(a -> new RemovedAttributeResponse(a.code(), a.label(),
                            a.value(), ReasonMessages.detail(a.reason(), "O atributo não vale na categoria nova.")))
                            .toList(),
                    change.newRequired().stream()
                            .map(a -> new NewRequiredAttributeResponse(a.code(), a.label(), a.variant())).toList(),
                    change.blockingSkus().stream().map(s -> new BlockingSkuResponse(s.sellerSku(),
                            s.reasons().stream().map(r -> ReasonMessages.detail(r,
                                    "A variação do SKU não vale na categoria nova.")).toList()))
                            .toList());
        }
    }

    public record ProductCheckResponse(
            @Schema(description = "Pendências de publicação do rascunho, como ficariam depois de salvar") List<PublishingIssueResponse> publishingIssues,
            @Schema(description = "Efeito da troca; ausente quando a categoria do rascunho é a atual", nullable = true) CategoryChangeResponse categoryChange) {
        static ProductCheckResponse of(ProductService.ProductCheck check) {
            return new ProductCheckResponse(PublishingIssueResponse.of(check.publishingIssues()),
                    check.categoryChange().map(CategoryChangeResponse::of).orElse(null));
        }
    }

    public record StatusChangeResponse(ProductTransition transition, ProductStatus from, ProductStatus to,
            @Schema(description = "Lado que fez a mudança, pela transição: a loja ou a moderação; a pessoa fica só no registro interno") TransitionSide actorSide,
            @Schema(description = "A mudança foi feita por quem consulta o histórico") boolean byCaller,
            @Schema(description = "Na rejeição e no bloqueio, o texto único derivado dos rótulos da época e do complemento", nullable = true) String reason,
            @Schema(description = "Códigos dos motivos prontos da rejeição ou do bloqueio; vazio nas demais transições") List<String> reasonCodes,
            @Schema(description = "Motivos prontos com o rótulo da época; vazio nas demais transições") List<ReasonLabelResponse> reasonLabels,
            @Schema(description = "Complemento livre do moderador", nullable = true) String note,
            Instant at) {
        static StatusChangeResponse of(StatusChange c, String callerSubject) {
            return new StatusChangeResponse(c.transition(), c.from(), c.to(), c.transition().side(),
                    c.actor().equals(callerSubject), c.reason(), c.reasonCodes(), ReasonLabelResponse.of(c.reasons()),
                    c.note(), c.at());
        }
    }

    /**
     * Indica se algum SKU tem saldo disponível; SKU sem saldo no mapa conta
     * como sem disponível.
     */
    static boolean anyAvailable(Product p, Map<UUID, StockLevel> stock) {
        return p.skus().stream().map(s -> stock.get(s.id())).anyMatch(level -> level != null && level.available() > 0);
    }

    /** Estado efetivo do produto com o saldo lido, ou o gravado quando o saldo não pôde ser lido. */
    static ProductStatus shownStatus(Product p, Optional<Map<UUID, StockLevel>> stock) {
        return stock.map(levels -> p.effectiveStatus(anyAvailable(p, levels))).orElse(p.status());
    }
}
