package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.api.BatchModels.BatchItemRequest;
import dev.marketplace.cataloginventory.application.Batch;
import dev.marketplace.cataloginventory.application.ModerationDecision;
import dev.marketplace.cataloginventory.application.ModerationReasonEntry;
import dev.marketplace.cataloginventory.domain.product.DecisionReason;
import dev.marketplace.cataloginventory.domain.product.ModerationReasonDefinition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Contratos HTTP da moderação: motivo da decisão, catálogo de motivos do tenant e lote. */
public final class ModerationModels {
  /**
   * Formato do código do motivo publicado no contrato. WHY: o contrato usa a sintaxe ECMAScript, que os clientes
   * entendem e que não tem quantificador possessivo; a linguagem aceita é a mesma de
   * {@link ModerationReasonDefinition#CODE_PATTERN}, que o {@code @Pattern} confere.
   */
  private static final String REASON_CODE_SCHEMA = "^[a-z]+(-[a-z]+)*$";

  private ModerationModels() {
  }

  @Schema(
      description = "Motivo da rejeição ou do bloqueio: ao menos um motivo pronto ativo do catálogo do tenant ou o complemento. O histórico guarda os códigos, a cópia dos rótulos da época e o complemento separados")
  public record DecisionRequest(
      @Schema(description = "Códigos de GET /moderation/reasons, ativos e válidos para a decisão",
          nullable = true) List<@NotBlank String> reasonCodes,
      @Schema(description = "Complemento livre; o limite vale só para ele, com quantos motivos houver",
          nullable = true) @Size(max = DecisionReason.NOTE_MAX_LENGTH) String note) {
    List<String> codes() {
      return codesOf(reasonCodes);
    }

    Optional<String> noteText() {
      return Optional.ofNullable(note);
    }
  }

  @Schema(description = "Motivo pronto de rejeição ou bloqueio do catálogo do tenant")
  public record ModerationReasonResponse(
      @Schema(description = "Código estável, enviado em reasonCodes; não muda depois de criado") String code,
      @Schema(description = "Rótulo em PT-BR; as decisões passadas guardam o rótulo da época") String label,
      @Schema(
          description = "Decisões em que o motivo vale: REJECT, BLOCK ou as duas") List<ModerationDecision> decisions,
      @Schema(description = "Ordem de exibição e de composição do texto; o código desempata") int position,
      @Schema(description = "Inativo não aparece para o moderador e é recusado nas decisões novas") boolean active,
      @Schema(description = "Versão do motivo, a mesma do ETag") long version) {
    static ModerationReasonResponse of(ModerationReasonEntry reason) {
      ModerationReasonDefinition d = reason.definition();
      return new ModerationReasonResponse(d.code(), d.label(),
          Stream.of(ModerationDecision.REJECT, ModerationDecision.BLOCK)
              .filter(decision -> d.appliesTo(decision.transition())).toList(),
          reason.position(), d.active(), reason.version());
    }
  }

  @Schema(description = "Motivo novo, ativo; sem position, entra no fim da lista")
  public record CreateModerationReasonRequest(
      @Schema(description = "Código estável em kebab-case, só com letras; não muda depois",
          pattern = REASON_CODE_SCHEMA) @NotBlank @Size(
              max = ModerationReasonDefinition.CODE_MAX_LENGTH) @Pattern(
                  regexp = "^" + ModerationReasonDefinition.CODE_PATTERN + "$") String code,
      @NotBlank @Size(max = ModerationReasonDefinition.LABEL_MAX_LENGTH) String label,
      @Schema(description = "REJECT, BLOCK ou as duas") @NotNull @Size(min = 1,
          max = 2) List<@NotNull ModerationDecision> decisions,
      @Schema(nullable = true) @Min(0) @Max(ModerationReasonEntry.MAX_POSITION) Integer position) {
    Optional<Integer> positionValue() {
      return Optional.ofNullable(position);
    }
  }

  @Schema(description = "Revisão do motivo: rótulo, decisões e posição; o código e a situação não mudam aqui")
  public record ReviseModerationReasonRequest(
      @NotBlank @Size(max = ModerationReasonDefinition.LABEL_MAX_LENGTH) String label,
      @Schema(description = "REJECT, BLOCK ou as duas") @NotNull @Size(min = 1,
          max = 2) List<@NotNull ModerationDecision> decisions,
      @NotNull @Min(0) @Max(ModerationReasonEntry.MAX_POSITION) Integer position) {
  }

  @Schema(
      description = "Decisão em lote: rejeitar e bloquear usam o mesmo motivo para todos os itens, e aprovar não leva motivo")
  public record ModerationBatchRequest(@NotNull ModerationDecision action,
      @NotNull @Size(min = 1, max = Batch.MAX_ITEMS) List<@NotNull @Valid BatchItemRequest> items,
      @Schema(description = "Códigos de GET /moderation/reasons, como no endpoint de um item",
          nullable = true) List<@NotBlank String> reasonCodes,
      @Schema(description = "Complemento livre, como no endpoint de um item",
          nullable = true) @Size(max = DecisionReason.NOTE_MAX_LENGTH) String note) {
    List<String> codes() {
      return codesOf(reasonCodes);
    }

    Optional<String> noteText() {
      return Optional.ofNullable(note);
    }
  }

  private static List<String> codesOf(List<String> reasonCodes) {
    return reasonCodes == null ? List.of() : reasonCodes;
  }
}
