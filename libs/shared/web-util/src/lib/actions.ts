import { type ReasonDetail, translateReason } from './reasons';

/** Ação que vale para o registro, mas que um motivo impede agora, como a API devolve em `blockedActions`. */
export interface BlockedAction {
  readonly action?: string;
  readonly reason?: ReasonDetail | null;
}

/** O que o servidor calculou sobre as ações de um registro para quem chama (ADR 031). */
export interface ServerActions {
  readonly actions?: readonly string[] | null;
  readonly blockedActions?: readonly BlockedAction[] | null;
}

/** Situação de uma ação no registro, pelo que o servidor respondeu. */
export interface ActionState {
  /** Pode ser executada agora (`actions`). */
  readonly allowed: boolean;
  /** Barrada: a tela mostra a ação desabilitada com este texto do servidor (`blockedActions`). */
  readonly blockedReason?: string;
}

/** Situação da ação no registro; a ação ausente das duas listas não vale e não aparece. */
export function actionState(source: ServerActions | null | undefined, code: string): ActionState {
  if (source?.actions?.includes(code)) {
    return { allowed: true };
  }
  const blocked = source?.blockedActions?.find(item => item.action === code);
  return blocked ? { allowed: false, blockedReason: translateReason(blocked.reason ?? {}) } : { allowed: false };
}

/** Indica se o servidor libera a ação para o registro agora. */
export function allowsAction(source: ServerActions | null | undefined, code: string): boolean {
  return source?.actions?.includes(code) === true;
}
