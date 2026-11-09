package dev.marketplace.cataloginventory.domain.offer;

import dev.marketplace.cataloginventory.domain.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import static dev.marketplace.cataloginventory.domain.DomainRuleViolation.invalid;

/** Preço promocional válido no intervalo [startsAt, endsAt). */
public record Promotion(UUID id, Money salePrice, Instant startsAt, Instant endsAt) {
    public static final Duration MAX_WINDOW = Duration.ofDays(366);

    /**
     * Exige {@code id} (nulo gera {@link NullPointerException}) e recusa com
     * {@code invalid-value} preço ausente, início que não anteceda o fim e
     * janela acima de {@link #MAX_WINDOW}.
     */
    public Promotion {
        Objects.requireNonNull(id, "id obrigatório");
        if (salePrice == null)
            throw invalid("Preço promocional é obrigatório");
        if (startsAt == null || endsAt == null || !startsAt.isBefore(endsAt))
            throw invalid("Promoção exige início anterior ao fim");
        if (Duration.between(startsAt, endsAt).compareTo(MAX_WINDOW) > 0)
            throw invalid("Promoção pode durar até " + MAX_WINDOW.toDays() + " dias");
    }

    /** Vigora em {@code instant}: início inclusivo e fim exclusivo. */
    public boolean activeAt(Instant instant) {
        return !instant.isBefore(startsAt) && instant.isBefore(endsAt);
    }

    /**
     * Indica se já terminou em {@code instant}; o fim é exclusivo, então
     * {@code instant == endsAt} conta como encerrada.
     */
    public boolean endedBy(Instant instant) {
        return !instant.isBefore(endsAt);
    }

    /** Fase em {@code instant}, com as mesmas fronteiras de {@link #activeAt} e {@link #endedBy}. */
    public PromotionPhase phaseAt(Instant instant) {
        if (endedBy(instant))
            return PromotionPhase.ENDED;
        return activeAt(instant) ? PromotionPhase.ACTIVE : PromotionPhase.SCHEDULED;
    }

    /**
     * Intervalos que só se tocam (um termina quando o outro começa) não se
     * sobrepõem.
     */
    public boolean overlaps(Promotion other) {
        return startsAt.isBefore(other.endsAt) && other.startsAt.isBefore(endsAt);
    }
}
