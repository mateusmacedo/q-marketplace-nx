package dev.marketplace.cataloginventory.domain.product;

import dev.marketplace.cataloginventory.domain.DomainReasons;
import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.Reason;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import static dev.marketplace.cataloginventory.domain.DomainRuleViolation.invalid;

/**
 * Motivo de uma rejeição ou de um bloqueio: os motivos prontos marcados, na ordem do catálogo do tenant e com o rótulo
 * da época, e o complemento livre ({@code note}), de até {@link #NOTE_MAX_LENGTH} caracteres. O texto único
 * ("Categoria incorreta. A gola não bate com a foto") é derivado dos dois ({@link #text()}).
 */
public record DecisionReason(ProductTransition decision, List<ReasonLabel> reasons, String note) {
  public static final int NOTE_MAX_LENGTH = 500;

  public DecisionReason {
    Objects.requireNonNull(decision, "decisão obrigatória");
    reasons = List.copyOf(reasons);
  }

  /**
   * Compõe o motivo com o catálogo do tenant (ativos e inativos, na ordem exibida). Recusa com
   * {@code invalid-moderation-reason} o código ausente, desativado ou de outra decisão ({@code unknown-reason},
   * {@code reason-inactive}, {@code reason-not-applicable}) e com {@code invalid-value} a falta de motivo ou o
   * complemento longo demais.
   */
  public static DecisionReason of(ProductTransition decision, List<ModerationReasonDefinition> catalog,
      Collection<String> codes, Optional<String> note) {
    if (decision != ProductTransition.REJECT && decision != ProductTransition.BLOCK)
      throw new IllegalArgumentException("Só rejeição e bloqueio têm motivo: " + decision);
    Map<String, ModerationReasonDefinition> byCode = new HashMap<>();
    catalog.forEach(reason -> byCode.putIfAbsent(reason.code(), reason));
    Set<String> chosen = new LinkedHashSet<>();
    List<Reason> refused = new ArrayList<>();
    for (String code : new LinkedHashSet<>(codes))
      refusal(code, byCode.get(code), decision).ifPresentOrElse(refused::add, () -> chosen.add(code));
    if (!refused.isEmpty())
      throw new DomainRuleViolation(DomainReasons.INVALID_MODERATION_REASON,
          "Motivo fora do catálogo de " + decision + "; consulte GET /moderation/reasons", refused);
    List<ReasonLabel> labels = new ArrayList<>();
    for (ModerationReasonDefinition reason : catalog)
      if (chosen.remove(reason.code()))
        labels.add(new ReasonLabel(reason.code(), reason.label()));
    String text = note.map(String::strip).filter(n -> !n.isEmpty()).orElse(null);
    if (text != null && text.length() > NOTE_MAX_LENGTH)
      throw invalid("O complemento tem até " + NOTE_MAX_LENGTH + " caracteres");
    if (labels.isEmpty() && text == null)
      throw invalid("Informe ao menos um motivo pronto ou o complemento");
    return new DecisionReason(decision, labels, text);
  }

  /** Códigos dos motivos marcados, na ordem do catálogo. */
  public List<String> codes() {
    return reasons.stream().map(ReasonLabel::code).toList();
  }

  /** Texto único: os rótulos, cada um com ponto final, e o complemento. */
  public String text() {
    return compose(reasons, note);
  }

  /**
   * Texto único de uma decisão gravada; nulo quando ela não tem motivo nem complemento, como as transições que não
   * são rejeição nem bloqueio.
   */
  public static String compose(List<ReasonLabel> reasons, String note) {
    List<String> parts = new ArrayList<>(reasons.stream().map(r -> r.label() + ".").toList());
    if (note != null && !note.isBlank())
      parts.add(note);
    return parts.isEmpty() ? null : String.join(" ", parts);
  }

  /**
   * Por que o código não vale para a decisão: ausente do catálogo, desativado ou de outra decisão; vazio quando vale.
   */
  private static Optional<Reason> refusal(String code, ModerationReasonDefinition reason,
      ProductTransition decision) {
    if (reason == null)
      return Optional.of(Reason.of(DomainReasons.UNKNOWN_REASON, code));
    if (!reason.active())
      return Optional.of(Reason.of(DomainReasons.REASON_INACTIVE, code)
          .withMessage("O motivo \"" + reason.label() + "\" foi desativado. Recarregue a lista de motivos."));
    if (!reason.appliesTo(decision))
      return Optional.of(Reason.of(DomainReasons.REASON_NOT_APPLICABLE, code)
          .withMessage("O motivo \"" + reason.label() + "\" não vale para " + name(decision) + "."));
    return Optional.empty();
  }

  private static String name(ProductTransition decision) {
    return decision == ProductTransition.REJECT ? "a rejeição" : "o bloqueio";
  }
}
