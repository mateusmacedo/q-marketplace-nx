import {
  listModerationReasonsResource,
  type ModerationDecision,
  type ModerationReasonResponse,
} from '@marketplace/catalog-inventory-admin-data-access';

/** Decisão do moderador que pede motivo: rejeitar ou bloquear. */
export type ReasonDecision = Exclude<ModerationDecision, 'APPROVE'>;

/**
 * Motivos do catálogo do tenant que valem para a decisão, lidos de `GET /moderation/reasons?decision=` na ordem do
 * servidor; o moderador recebe só os ativos. Sem decisão (modal fechado), não chama a API. A feature não guarda lista
 * de motivos: o catálogo é dado de cada tenant, mantido em Plataforma (ADR 030).
 */
export function moderationReasonsResource(decision: () => ReasonDecision | null | undefined) {
  return listModerationReasonsResource(() => {
    const current = decision();
    return current ? { query: { decision: current } } : undefined;
  });
}

/** Opções do modal: os motivos ativos que aceitam a decisão, na ordem recebida. */
export function reasonOptions(
  reasons: readonly ModerationReasonResponse[] | null | undefined,
  decision: ReasonDecision,
): ModerationReasonResponse[] {
  return (reasons ?? []).filter(
    (reason) =>
      !!reason.code && reason.active !== false && (reason.decisions ?? []).includes(decision),
  );
}
