package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.application.ProductRepository.QueuePosition;
import dev.marketplace.cataloginventory.domain.product.DecisionReason;
import dev.marketplace.cataloginventory.domain.product.Product;
import io.smallrye.mutiny.Uni;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import static dev.marketplace.cataloginventory.application.ProductSupport.sync;

/**
 * Trust &amp; Safety: fila de moderação, aprovação, rejeição e bloqueio de
 * produtos.
 */
public final class ModerationService {
    private static final String SEPARATOR = "|";
    private final ProductSupport support;
    private final ModerationReasonRepository reasons;

    public ModerationService(ProductRepository products, CategoryRepository categories,
            SellerEligibilityRepository sellers,
            OfferRepository offers, ModerationReasonRepository reasons, Clock clock) {
        this.support = new ProductSupport(products, categories, sellers, offers, clock);
        this.reasons = reasons;
    }

    /**
     * Fila de moderação do tenant, do que chegou primeiro ao mais recente, por
     * cursor (entrada na fila e id do último visto). Exige
     * {@code MODERATE_PRODUCTS}; cursor malformado ou {@code limit} fora de 1
     * a 100 lança {@link InvalidInputException}.
     */
    public Uni<Page<Product>> queue(Actor actor, Optional<String> cursorKey, int limit) {
        return Uni.createFrom().deferred(() -> {
            actor.require(Capability.MODERATE_PRODUCTS);
            Optional<QueuePosition> after = cursorKey.map(ModerationService::position);
            return Page.fetch(limit,
                    fetchLimit -> support.products().moderationQueue(actor.tenantId(), after, fetchLimit),
                    ModerationService::cursorOf);
        });
    }

    /**
     * Aprova o produto em {@code PENDING_MODERATION} e o torna {@code ACTIVE},
     * reavaliando completude ({@code product-not-publishable}) e seller
     * ({@code seller-not-eligible}). Exige {@code MODERATE_PRODUCTS};
     * {@code ifMatch} ausente = sem checagem.
     */
    public Uni<Product> approve(Actor actor, UUID id, Optional<Long> ifMatch) {
        return support.mutateAsModerator(actor, id, ifMatch, product -> support.publicationOf(product)
                .flatMap(publication -> support.sellerOf(product)
                        .invoke(seller -> product.approve(publication.category().effectiveAttributes(),
                                publication.category().usable(), publication.offeredSkus(), seller, actor.subject(),
                                support.now()))));
    }

    /**
     * Devolve o produto de {@code PENDING_MODERATION} para {@code DRAFT} com os
     * motivos do catálogo do tenant e o complemento ({@link DecisionReason#of}),
     * conferidos antes de ler o produto. Exige {@code MODERATE_PRODUCTS};
     * {@code ifMatch} ausente = sem checagem.
     */
    public Uni<Product> reject(Actor actor, UUID id, Optional<Long> ifMatch, List<String> reasonCodes,
            Optional<String> note) {
        return Uni.createFrom().deferred(() -> {
            actor.require(Capability.MODERATE_PRODUCTS);
            return decisionReason(actor, ModerationDecision.REJECT, reasonCodes, note)
                    .flatMap(reason -> reject(actor, id, ifMatch, reason));
        });
    }

    /**
     * Bloqueia o produto (qualquer estado menos {@code BLOCKED} e
     * {@code ARCHIVED}), tira-o da vitrine e publica o evento de bloqueio, com o
     * motivo conferido como na rejeição. Exige {@code MODERATE_PRODUCTS};
     * {@code ifMatch} ausente = sem checagem.
     */
    public Uni<Product> block(Actor actor, UUID id, Optional<Long> ifMatch, List<String> reasonCodes,
            Optional<String> note) {
        return Uni.createFrom().deferred(() -> {
            actor.require(Capability.MODERATE_PRODUCTS);
            return decisionReason(actor, ModerationDecision.BLOCK, reasonCodes, note)
                    .flatMap(reason -> block(actor, id, ifMatch, reason));
        });
    }

    /**
     * Desbloqueia o produto, que volta para {@code PENDING_MODERATION} e
     * precisa de nova aprovação; só vale em {@code BLOCKED}. Exige
     * {@code MODERATE_PRODUCTS}; {@code ifMatch} ausente = sem checagem.
     */
    public Uni<Product> unblock(Actor actor, UUID id, Optional<Long> ifMatch) {
        return support.mutateAsModerator(actor, id, ifMatch,
                sync(product -> product.unblock(actor.subject(), support.now())));
    }

    /**
     * Aplica a decisão a cada produto como o endpoint de um item, com o mesmo
     * motivo para todos, conferido uma vez antes do primeiro item. A aprovação
     * com motivo, itens fora de 1 a {@link Batch#MAX_ITEMS} ou id repetido
     * lançam {@link InvalidInputException}. Exige {@code MODERATE_PRODUCTS}.
     */
    public Uni<List<Batch.Outcome<Product>>> batch(Actor actor, ModerationDecision decision, List<String> reasonCodes,
            Optional<String> note, List<Batch.Item> items) {
        return Uni.createFrom().deferred(() -> {
            actor.require(Capability.MODERATE_PRODUCTS);
            Batch.requireItems(items);
            if (decision == ModerationDecision.APPROVE) {
                if (!reasonCodes.isEmpty() || note.filter(n -> !n.isBlank()).isPresent())
                    throw new InvalidInputException("A aprovação não leva motivo");
                return Batch.run(items, (itemId, version) -> approve(actor, itemId, version));
            }
            return decisionReason(actor, decision, reasonCodes, note)
                    .flatMap(reason -> Batch.run(items, operation(actor, decision, reason)));
        });
    }

    private BiFunction<UUID, Optional<Long>, Uni<Product>> operation(Actor actor, ModerationDecision decision,
            DecisionReason reason) {
        return decision == ModerationDecision.REJECT ? (id, version) -> reject(actor, id, version, reason)
                : (id, version) -> block(actor, id, version, reason);
    }

    /**
     * Confere os códigos contra o catálogo do tenant, lido só quando há código; só o complemento dispensa a leitura,
     * e o tenant sem catálogo decide assim.
     */
    private Uni<DecisionReason> decisionReason(Actor actor, ModerationDecision decision, List<String> codes,
            Optional<String> note) {
        if (codes.isEmpty())
            return Uni.createFrom().item(() -> DecisionReason.of(decision.transition(), List.of(), codes, note));
        return reasons.catalog(actor.tenantId()).map(catalog -> DecisionReason.of(decision.transition(),
                catalog.stream().map(ModerationReasonEntry::definition).toList(), codes, note));
    }

    private Uni<Product> reject(Actor actor, UUID id, Optional<Long> ifMatch, DecisionReason reason) {
        return support.mutateAsModerator(actor, id, ifMatch,
                sync(product -> product.reject(reason, actor.subject(), support.now())));
    }

    private Uni<Product> block(Actor actor, UUID id, Optional<Long> ifMatch, DecisionReason reason) {
        return support.mutateAsModerator(actor, id, ifMatch,
                sync(product -> product.block(reason, actor.subject(), support.now())));
    }

    /** Chave do cursor depois de {@code last}: entrada na fila e id. */
    private static String cursorOf(Product last) {
        return last.moderationQueuedAt().orElseThrow() + SEPARATOR + last.id();
    }

    private static QueuePosition position(String key) {
        String[] parts = key.split("\\" + SEPARATOR, 2);
        try {
            if (parts.length != 2)
                throw new IllegalArgumentException("sem separador");
            return new QueuePosition(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new InvalidInputException("cursor inválido");
        }
    }
}
