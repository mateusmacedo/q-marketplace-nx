import type { ModerationDecision } from '@marketplace/catalog-inventory-admin-data-access';
import type { BatchVerb } from '@marketplace/shared-web-util';

/**
 * Apresentação de uma decisão do moderador, pelo código que o servidor devolve em `actions`. O servidor decide quais
 * decisões valem para o produto e para quem chama (ADR 031); aqui fica só o texto, a cor e o toast.
 */
export interface DecisionPresentation {
  readonly code: ModerationDecision;
  /** Nome no botão do detalhe, na barra de lote e no modal ("Rejeitar"). */
  readonly label: string;
  /** Rótulo durante a chamada em lote ("Rejeitando"). */
  readonly runningLabel: string;
  readonly color: 'success' | 'warning' | 'danger';
  /** Particípio para o resumo do lote ("6 rejeitados"). */
  readonly done: BatchVerb;
  /** Toast da decisão num item só. */
  readonly successMessage: string;
  /** Efeito da decisão num produto, mostrado no modal. */
  readonly effect: string;
  /** Efeito da decisão no lote. */
  readonly batchEffect: string;
}

/** Decisões na ordem dos botões do detalhe e da barra de lote (proposta de design, seção "8. Fila de moderação"). */
export const DECISIONS: readonly DecisionPresentation[] = [
  {
    code: 'APPROVE',
    label: 'Aprovar',
    runningLabel: 'Aprovando',
    color: 'success',
    done: { one: 'aprovado', many: 'aprovados' },
    successMessage: 'Produto aprovado.',
    effect: 'O produto é publicado.',
    batchEffect:
      'Os produtos são publicados sem que você abra cada um. As recusas aparecem no resultado, com o motivo.',
  },
  {
    code: 'REJECT',
    label: 'Rejeitar',
    runningLabel: 'Rejeitando',
    color: 'warning',
    done: { one: 'rejeitado', many: 'rejeitados' },
    successMessage: 'Produto rejeitado. Ele voltou ao rascunho do lojista.',
    effect: 'O produto volta ao rascunho do lojista.',
    batchEffect: 'Os produtos voltam ao rascunho dos lojistas.',
  },
  {
    code: 'BLOCK',
    label: 'Bloquear',
    runningLabel: 'Bloqueando',
    color: 'danger',
    done: { one: 'bloqueado', many: 'bloqueados' },
    successMessage: 'Produto bloqueado. Ele saiu da vitrine.',
    effect: 'O produto sai da vitrine na hora.',
    batchEffect: 'Os produtos saem da vitrine na hora.',
  },
];

export function decisionPresentation(code: ModerationDecision): DecisionPresentation {
  return DECISIONS.find((decision) => decision.code === code) ?? DECISIONS[0];
}
