package dev.marketplace.cataloginventory.domain.product;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;
import static dev.marketplace.cataloginventory.domain.DomainRuleViolation.invalid;
import static dev.marketplace.cataloginventory.domain.DomainRuleViolation.requireText;

/**
 * Motivo pronto de rejeição ou bloqueio, mantido como dado no catálogo de cada tenant. O código é gravado no histórico
 * e publicado nos eventos, por isso nunca muda depois de criado; o rótulo, as decisões em que vale e a situação mudam,
 * sem alterar decisões passadas, que guardam o rótulo da época.
 */
public record ModerationReasonDefinition(String code, String label, Set<ProductTransition> decisions,
    boolean active) {
  public static final int CODE_MAX_LENGTH = 60;
  public static final int LABEL_MAX_LENGTH = 120;
  /**
   * Kebab-case só com letras, o mesmo formato que o contrato de eventos aceita em {@code reasonCodes}. WHY: os
   * quantificadores possessivos aceitam a mesma linguagem de {@code [a-z]+(-[a-z]+)*} sem o retrocesso recursivo que
   * estoura a pilha em entradas longas.
   */
  public static final String CODE_PATTERN = "[a-z]++(?:-[a-z]++)*+";
  private static final Pattern CODE_FORMAT = Pattern.compile(CODE_PATTERN);
  private static final Set<ProductTransition> WITH_REASON = EnumSet.of(ProductTransition.REJECT,
      ProductTransition.BLOCK);

  /**
   * Recusa com {@code invalid-value} o código fora do formato, o rótulo vazio ou longo demais e as decisões vazias ou
   * com transição que não leva motivo: só rejeição e bloqueio têm motivo.
   */
  public ModerationReasonDefinition {
    if (code == null || code.length() > CODE_MAX_LENGTH || !CODE_FORMAT.matcher(code).matches())
      throw invalid("Código do motivo deve ser kebab-case minúsculo, só com letras, com até " + CODE_MAX_LENGTH
          + " caracteres");
    label = requireText(label == null ? null : label.strip(), LABEL_MAX_LENGTH,
        "Rótulo do motivo é obrigatório e tem até " + LABEL_MAX_LENGTH + " caracteres");
    decisions = requireDecisions(decisions);
  }

  /** Indica se o motivo vale para a decisão, ativo ou não. */
  public boolean appliesTo(ProductTransition decision) {
    return decisions.contains(decision);
  }

  /** Mesmo código e situação, com o rótulo e as decisões novos. */
  public ModerationReasonDefinition revised(String label, Set<ProductTransition> decisions) {
    return new ModerationReasonDefinition(code, label, decisions, active);
  }

  /** Mesmo motivo na situação pedida. */
  public ModerationReasonDefinition withActive(boolean active) {
    return new ModerationReasonDefinition(code, label, decisions, active);
  }

  private static Set<ProductTransition> requireDecisions(Collection<ProductTransition> decisions) {
    if (decisions == null || decisions.isEmpty())
      throw invalid("Informe ao menos uma decisão do motivo: REJECT ou BLOCK");
    if (!WITH_REASON.containsAll(decisions))
      throw invalid("Só a rejeição e o bloqueio levam motivo");
    return Collections.unmodifiableSet(EnumSet.copyOf(decisions));
  }
}
