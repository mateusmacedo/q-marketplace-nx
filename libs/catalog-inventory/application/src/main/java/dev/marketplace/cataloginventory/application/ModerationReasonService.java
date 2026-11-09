package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.Reason;
import dev.marketplace.cataloginventory.domain.product.ModerationReasonDefinition;
import dev.marketplace.cataloginventory.domain.product.ProductTransition;
import io.smallrye.mutiny.Uni;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Catálogo de motivos de moderação do tenant: o moderador lê os ativos, e quem tem
 * {@code MANAGE_MODERATION_REASONS} cria, revisa, ativa e desativa. Não há exclusão, porque o código fica no
 * histórico e nos eventos.
 */
public final class ModerationReasonService {
    /** Motivos ativos por tenant, o que o moderador vê de uma vez; desativar um motivo abre a vaga. */
    public static final int MAX_ACTIVE_PER_TENANT = 100;
    /**
     * Teto de proteção do catálogo inteiro, contando os inativos, que cada decisão de moderação lê; fica bem acima do
     * de ativos para que aposentar motivos não esgote a criação.
     */
    public static final int MAX_PER_TENANT = 1000;
    private final ModerationReasonRepository reasons;
    private final Clock clock;

    public ModerationReasonService(ModerationReasonRepository reasons, Clock clock) {
        this.reasons = reasons;
        this.clock = clock;
    }

    /**
     * Motivos do tenant em ordem de exibição; com {@code decision}, só os que valem para ela. O moderador vê só os
     * ativos; {@code includeInactive} exige {@code MANAGE_MODERATION_REASONS}. Sem nenhuma das duas capacidades, lança
     * {@link AccessDeniedException}.
     */
    public Uni<List<ModerationReasonEntry>> list(Actor actor, boolean includeInactive,
            Optional<ModerationDecision> decision) {
        return Uni.createFrom().deferred(() -> {
            if (!actor.can(Capability.MODERATE_PRODUCTS) && !actor.can(Capability.MANAGE_MODERATION_REASONS))
                throw new AccessDeniedException("Operação exige a permissão " + Capability.MODERATE_PRODUCTS);
            if (includeInactive)
                actor.require(Capability.MANAGE_MODERATION_REASONS);
            return reasons.catalog(actor.tenantId()).map(all -> all.stream()
                    .filter(r -> includeInactive || r.definition().active())
                    .filter(r -> decision.map(d -> r.definition().appliesTo(d.transition())).orElse(true))
                    .toList());
        });
    }

    /** Motivo do código, ativo ou não. Exige {@code MANAGE_MODERATION_REASONS}; inexistente no tenant: 404. */
    public Uni<ModerationReasonEntry> get(Actor actor, String code) {
        return Uni.createFrom().deferred(() -> {
            actor.require(Capability.MANAGE_MODERATION_REASONS);
            return load(actor.tenantId(), code);
        });
    }

    /**
     * Cria o motivo ativo; sem {@code position}, ele entra no fim da lista. Exige
     * {@code MANAGE_MODERATION_REASONS}; código, rótulo ou decisões inválidos: {@code invalid-value}; tenant com
     * {@link #MAX_PER_TENANT} motivos, contando os inativos: {@code moderation-reason-catalog-limit}; com
     * {@link #MAX_ACTIVE_PER_TENANT} ativos: {@code moderation-reason-limit}; código já usado:
     * {@link DuplicateException}.
     */
    public Uni<ModerationReasonEntry> create(Actor actor, String code, String label,
            Collection<ModerationDecision> decisions, Optional<Integer> position) {
        return Uni.createFrom().deferred(() -> {
            actor.require(Capability.MANAGE_MODERATION_REASONS);
            ModerationReasonDefinition definition = new ModerationReasonDefinition(code, label,
                    transitions(decisions), true);
            position.ifPresent(ModerationReasonService::requirePosition);
            UUID tenant = actor.tenantId();
            return reasons.catalog(tenant).flatMap(all -> {
                if (all.size() >= MAX_PER_TENANT)
                    throw new DomainRuleViolation(
                            Reason.of(ApplicationReasons.MODERATION_REASON_CATALOG_LIMIT, MAX_PER_TENANT),
                            "O catálogo guarda até " + MAX_PER_TENANT + " motivos, contando os inativos");
                requireActiveRoom(all);
                int last = all.stream().mapToInt(ModerationReasonEntry::position).max().orElse(-1);
                Instant now = clock.instant();
                ModerationReasonEntry created = new ModerationReasonEntry(tenant, definition,
                        position.orElse(Math.min(last + 1, ModerationReasonEntry.MAX_POSITION)), 0, now, now);
                return reasons.insert(created).replaceWith(created);
            });
        });
    }

    /**
     * Troca rótulo, decisões e posição, sem mudar o código nem a situação; decisões passadas guardam o rótulo da
     * época. Exige {@code MANAGE_MODERATION_REASONS}; inexistente: 404; {@code ifMatch} divergente: 412.
     */
    public Uni<ModerationReasonEntry> revise(Actor actor, String code, Optional<Long> ifMatch, String label,
            Collection<ModerationDecision> decisions, int position) {
        return Uni.createFrom().deferred(() -> {
            actor.require(Capability.MANAGE_MODERATION_REASONS);
            Set<ProductTransition> transitions = transitions(decisions);
            requirePosition(position);
            return change(actor, code, ifMatch, current -> current.next(
                    current.definition().revised(label, transitions), position, clock.instant()));
        });
    }

    /**
     * Volta a oferecer o motivo ao moderador; já ativo, não grava nada. Mesmas recusas de {@link #revise} e, com
     * {@link #MAX_ACTIVE_PER_TENANT} ativos, {@code moderation-reason-limit}.
     */
    public Uni<ModerationReasonEntry> activate(Actor actor, String code, Optional<Long> ifMatch) {
        return Uni.createFrom().deferred(() -> {
            actor.require(Capability.MANAGE_MODERATION_REASONS);
            return reasons.catalog(actor.tenantId()).flatMap(all -> change(actor, code, ifMatch, current -> {
                if (current.definition().active())
                    return current;
                requireActiveRoom(all);
                return withActive(current, true);
            }));
        });
    }

    /**
     * Tira o motivo da lista do moderador e o recusa nas decisões novas ({@code reason-inactive}); já inativo, não
     * grava nada. Mesmas recusas de {@link #revise}.
     */
    public Uni<ModerationReasonEntry> deactivate(Actor actor, String code, Optional<Long> ifMatch) {
        return Uni.createFrom().deferred(() -> {
            actor.require(Capability.MANAGE_MODERATION_REASONS);
            return change(actor, code, ifMatch,
                    current -> current.definition().active() ? withActive(current, false) : current);
        });
    }

    private ModerationReasonEntry withActive(ModerationReasonEntry current, boolean active) {
        return current.next(current.definition().withActive(active), current.position(), clock.instant());
    }

    private static void requireActiveRoom(List<ModerationReasonEntry> all) {
        if (all.stream().filter(r -> r.definition().active()).count() >= MAX_ACTIVE_PER_TENANT)
            throw new DomainRuleViolation(Reason.of(ApplicationReasons.MODERATION_REASON_LIMIT, MAX_ACTIVE_PER_TENANT),
                    "O catálogo aceita até " + MAX_ACTIVE_PER_TENANT + " motivos ativos");
    }

    /** Confere a versão, aplica {@code change} e grava só quando a versão avançou. */
    private Uni<ModerationReasonEntry> change(Actor actor, String code, Optional<Long> ifMatch,
            UnaryOperator<ModerationReasonEntry> change) {
        return load(actor.tenantId(), code).flatMap(current -> {
            Versions.requireMatch(ifMatch, current.version());
            ModerationReasonEntry changed = change.apply(current);
            if (changed == current)
                return Uni.createFrom().item(current);
            return reasons.update(changed, current.version()).replaceWith(changed);
        });
    }

    private Uni<ModerationReasonEntry> load(UUID tenant, String code) {
        return reasons.findByCode(tenant, code)
                .map(found -> found.orElseThrow(() -> new NotFoundException("Motivo de moderação")));
    }

    private static Set<ProductTransition> transitions(Collection<ModerationDecision> decisions) {
        Set<ProductTransition> transitions = EnumSet.noneOf(ProductTransition.class);
        if (decisions != null)
            decisions.forEach(d -> transitions.add(d.transition()));
        return transitions;
    }

    private static void requirePosition(int position) {
        if (position < 0 || position > ModerationReasonEntry.MAX_POSITION)
            throw new InvalidInputException("Posição do motivo vai de 0 a " + ModerationReasonEntry.MAX_POSITION);
    }
}
