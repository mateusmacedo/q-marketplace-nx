package dev.marketplace.cataloginventory.domain.product;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Registro imutável de uma transição do produto, para auditoria e backoffice. Na rejeição e no bloqueio,
 * {@code reasons} são os motivos prontos com o rótulo da época e {@code note}, o complemento; nas demais transições,
 * a lista é vazia e o complemento, nulo.
 */
public record StatusChange(UUID id, ProductTransition transition, ProductStatus from, ProductStatus to, String actor,
    List<ReasonLabel> reasons, String note, Instant at) {
  public StatusChange {
    reasons = List.copyOf(reasons);
  }

  /** Códigos dos motivos prontos, na ordem do catálogo da época. */
  public List<String> reasonCodes() {
    return reasons.stream().map(ReasonLabel::code).toList();
  }

  /** Texto único derivado dos rótulos e do complemento ({@link DecisionReason#compose}); nulo sem os dois. */
  public String reason() {
    return DecisionReason.compose(reasons, note);
  }
}
