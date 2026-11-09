import type {
  ProductTransition,
  ReasonLabelResponse,
  StatusChangeResponse
} from '@marketplace/catalog-inventory-admin-data-access';

/** Rótulo de cada transição do histórico; a transição que o painel não conhece usa o rótulo genérico. */
const TRANSITION_LABELS: Readonly<Record<ProductTransition, string>> = {
  CREATE: 'Criado',
  SUBMIT: 'Enviado para moderação',
  APPROVE: 'Aprovado',
  REJECT: 'Rejeitado',
  BLOCK: 'Bloqueado',
  UNBLOCK: 'Desbloqueado',
  PAUSE: 'Pausado',
  RESUME: 'Reativado',
  ARCHIVE: 'Arquivado',
  REVIEW_REQUIRED: 'Voltou para a moderação'
};

/** Decisões de moderação que o histórico mostra em destaque, com os motivos marcados e o complemento. */
const DECISIONS: readonly string[] = ['REJECT', 'BLOCK'];

/** Rótulo da transição ("Rejeitado"). */
export function transitionLabel(transition: string | null | undefined): string {
  return TRANSITION_LABELS[transition as ProductTransition] ?? 'Mudança de status';
}

/** Motivos e complemento de uma rejeição ou de um bloqueio, como o moderador marcou e escreveu na época. */
export interface ModerationDecision {
  /** Rótulos da época, na ordem do catálogo de então; renomear o motivo depois não os muda. */
  readonly labels: readonly string[];
  readonly note: string | null;
}

/**
 * Motivos e complemento da entrada, separados, para a rejeição e o bloqueio; `null` nas outras transições. O texto
 * único `reason`, só derivado, nunca é interpretado.
 */
export function moderationDecision(
  change: Pick<StatusChangeResponse, 'transition' | 'reasonLabels' | 'note'> | null | undefined
): ModerationDecision | null {
  if (!change?.transition || !DECISIONS.includes(change.transition)) {
    return null;
  }
  return decisionOf(change.reasonLabels, change.note);
}

/**
 * Motivos e complemento de uma decisão: os rótulos da época, sem os vazios, e o complemento, quando há. O código do
 * motivo nunca aparece na tela.
 */
export function decisionOf(
  reasonLabels: readonly ReasonLabelResponse[] | null | undefined,
  note: string | null | undefined
): ModerationDecision {
  return {
    labels: (reasonLabels ?? []).map(reason => reason.label?.trim() ?? '').filter(Boolean),
    note: note?.trim() ? note : null
  };
}

/** Última entrada do histórico com a transição, como o bloqueio que deixou o produto bloqueado. */
export function lastTransition(
  history: readonly StatusChangeResponse[] | null | undefined,
  transition: ProductTransition
): StatusChangeResponse | undefined {
  return [...(history ?? [])].reverse().find(change => change.transition === transition);
}

/** Entradas do histórico da mais recente para a mais antiga; a API devolve em ordem cronológica. */
export function newestFirst(history: readonly StatusChangeResponse[] | null | undefined): StatusChangeResponse[] {
  return [...(history ?? [])].reverse();
}
