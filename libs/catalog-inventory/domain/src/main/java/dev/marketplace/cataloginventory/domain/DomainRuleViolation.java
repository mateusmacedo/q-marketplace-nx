package dev.marketplace.cataloginventory.domain;

import java.util.List;
import java.util.Objects;

/**
 * Recusa de negócio tipada. {@code reason} é o motivo da própria recusa, com o
 * código estável para clientes e backoffice; {@code reasons} lista motivos
 * identificáveis (ex.: {@code missing-attribute:cor}). Não é falha técnica e
 * nunca deve disparar retentativa.
 */
public class DomainRuleViolation extends RuntimeException {
  private final Reason reason;
  private final List<Reason> reasons;

  /** Recusa sem motivos detalhados e com o texto padrão do código. */
  public DomainRuleViolation(ReasonCode code, String message) {
    this(Reason.of(code), message, List.of());
  }

  /** Recusa com o código e os motivos detalhados, em cópia imutável. */
  public DomainRuleViolation(ReasonCode code, String message, List<Reason> reasons) {
    this(Reason.of(code), message, reasons);
  }

  /**
   * Recusa sem motivos detalhados; {@code reason} leva os valores do caso
   * (ex.: o limite vigente) e, se tiver, o texto ao usuário.
   */
  public DomainRuleViolation(Reason reason, String message) {
    this(reason, message, List.of());
  }

  public DomainRuleViolation(Reason reason, String message, List<Reason> reasons) {
    super(message);
    this.reason = Objects.requireNonNull(reason, "reason");
    this.reasons = List.copyOf(reasons);
  }

  public String code() {
    return reason.code();
  }

  /** Motivo da própria recusa. */
  public Reason reason() {
    return reason;
  }

  /** Motivos detalhados na forma compacta ({@code missing-attribute:cor}). */
  public List<String> reasons() {
    return reasons.stream().map(Reason::key).toList();
  }

  /**
   * O que mostrar a quem recebe a recusa: os motivos detalhados ou, sem eles,
   * o motivo da própria recusa.
   */
  public List<Reason> details() {
    return reasons.isEmpty() ? List.of(reason) : reasons;
  }

  /**
   * Cria a recusa genérica de valor inválido, com código
   * {@code invalid-value}; {@code message} é o próprio texto ao usuário.
   */
  public static DomainRuleViolation invalid(String message) {
    return new DomainRuleViolation(Reason.of(DomainReasons.INVALID_VALUE).withMessage(message), message);
  }

  /**
   * Devolve {@code value} se tiver conteúdo e até {@code max} caracteres; nulo,
   * em branco ou longo demais recusa com {@code invalid-value} e
   * {@code message}.
   */
  public static String requireText(String value, int max, String message) {
    if (value == null || value.isBlank() || value.length() > max)
      throw invalid(message);
    return value;
  }
}
