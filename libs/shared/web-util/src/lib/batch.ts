import { isProblem, type Problem, toProblem } from './problem';
import { GENERIC_REASON_MESSAGE } from './reasons';

/** Itens por chamada dos endpoints em lote da API. */
// WHY: cópia pendente (ADR 031); saída: maxItems do pedido no schema gerado (plugin @hey-api/schemas).
export const BATCH_LIMIT = 100;

/** Contagem do resultado de um lote. */
export interface BatchCounts {
  /** Itens em que a ação deu certo. */
  readonly succeeded: number;
  /** Itens que a API recusou. */
  readonly failed: number;
  /** Selecionados em que a ação não vale no status e que nem foram enviados. */
  readonly ignored: number;
}

/** Particípio da ação no singular e no plural ("pausado" e "pausados"), para o resumo. */
export interface BatchVerb {
  readonly one: string;
  readonly many: string;
  /** Item de nome feminino, como categoria, para o resumo concordar ("1 ignorada"). */
  readonly feminine?: boolean;
}

/** "1 produto" ou "3 produtos". */
export function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`;
}

/** Resumo do lote para o toast: "6 pausados, 2 com falha, 1 ignorado". As partes zeradas ficam de fora. */
export function batchSummary(counts: BatchCounts, verb: BatchVerb): string {
  const parts = [
    counts.succeeded ? plural(counts.succeeded, verb.one, verb.many) : '',
    counts.failed ? `${counts.failed} com falha` : '',
    counts.ignored
      ? verb.feminine
        ? plural(counts.ignored, 'ignorada', 'ignoradas')
        : plural(counts.ignored, 'ignorado', 'ignorados')
      : ''
  ].filter(Boolean);
  return parts.length ? parts.join(', ') : plural(0, verb.one, verb.many);
}

/** Divide os itens em grupos de até `size`, para respeitar o limite de cada chamada em lote. */
export function chunk<T>(items: readonly T[], size: number = BATCH_LIMIT): T[][] {
  if (size < 1) {
    throw new RangeError('O tamanho do grupo deve ser positivo');
  }
  const groups: T[][] = [];
  for (let start = 0; start < items.length; start += size) {
    groups.push(items.slice(start, start + size));
  }
  return groups;
}

/** Resultado de um item, como os endpoints em lote respondem, na posição em que o item veio no pedido. */
export interface BatchItemResult {
  readonly id?: string;
  /** `SUCCEEDED` quando o item foi gravado; qualquer outro valor conta como falha. */
  readonly outcome?: string;
  /** Problema que o endpoint de um item responderia; ausente no sucesso. */
  readonly problem?: unknown;
}

export interface BatchRunOptions<T, R extends BatchItemResult> {
  /** Selecionados que aceitam a ação, na ordem da tela; os que não aceitam ficam de fora e contam como ignorados. */
  readonly items: readonly T[];
  readonly id: (item: T) => string;
  /** Tamanho do grupo de cada chamada: o `maxItems` de `items` no schema do pedido (`fieldRules`). */
  readonly size: number;
  /** Envia um grupo ao endpoint em lote e devolve os resultados (`results` da resposta). */
  readonly send: (group: readonly T[]) => Promise<readonly R[] | null | undefined>;
  /** Interrompe antes do próximo grupo, como ao sair da tela; o grupo já enviado é processado inteiro pelo servidor. */
  readonly signal?: AbortSignal;
}

export interface BatchRunSuccess<T, R> {
  readonly item: T;
  /** Resultado do servidor, com a situação e a versão novas. */
  readonly result: R;
}

export interface BatchRunFailure<T> {
  readonly item: T;
  /** Problema do item, para a mensagem do alerta (`problemMessages`). */
  readonly problem: Problem;
}

/** Desfecho do lote inteiro, item a item. */
export interface BatchRun<T, R> {
  readonly succeeded: readonly BatchRunSuccess<T, R>[];
  /** Itens que o servidor recusou; continuam selecionados para uma nova tentativa. */
  readonly failed: readonly BatchRunFailure<T>[];
  /**
   * Itens sem resposta do servidor: os da chamada que falhou inteira, os dos grupos seguintes, que não foram enviados,
   * e os que sobraram ao interromper. Continuam selecionados.
   */
  readonly unanswered: readonly T[];
  /** Problema da chamada que falhou inteira (rede, sessão, permissão), para o alerta da tela. */
  readonly error?: Problem;
  /** O lote parou antes do fim, por uma chamada que falhou inteira ou pela interrupção. */
  readonly interrupted: boolean;
}

/**
 * Envia os itens em grupos de até `size`, um grupo por vez, e separa o desfecho de cada item. Uma chamada que falha
 * inteira para o lote: os itens dela e dos grupos seguintes ficam em `unanswered`, com o problema em `error`.
 */
export async function runBatch<T, R extends BatchItemResult>(options: BatchRunOptions<T, R>): Promise<BatchRun<T, R>> {
  const succeeded: BatchRunSuccess<T, R>[] = [];
  const failed: BatchRunFailure<T>[] = [];
  const unanswered: T[] = [];
  const groups = chunk(options.items, options.size);
  for (let index = 0; index < groups.length; index++) {
    const group = groups[index];
    if (options.signal?.aborted) {
      return { succeeded, failed, unanswered: [...unanswered, ...groups.slice(index).flat()], interrupted: true };
    }
    let results: readonly R[];
    try {
      results = (await options.send(group)) ?? [];
    } catch (error) {
      return {
        succeeded,
        failed,
        unanswered: [...unanswered, ...groups.slice(index).flat()],
        error: toProblem(error),
        interrupted: true
      };
    }
    const byId = new Map(results.filter(result => result.id).map(result => [result.id, result]));
    group.forEach((item, position) => {
      const result = byId.get(options.id(item)) ?? (byId.size ? undefined : results[position]);
      if (!result) {
        unanswered.push(item);
      } else if (result.outcome === SUCCEEDED) {
        succeeded.push({ item, result });
      } else {
        failed.push({ item, problem: itemProblem(result.problem) });
      }
    });
  }
  return { succeeded, failed, unanswered, interrupted: false };
}

/** Contagem do lote para o resumo (`batchSummary`); os itens sem resposta contam como falha. */
export function batchRunCounts(run: BatchRun<unknown, unknown>, ignored = 0): BatchCounts {
  return { succeeded: run.succeeded.length, failed: run.failed.length + run.unanswered.length, ignored };
}

const SUCCEEDED = 'SUCCEEDED';

function itemProblem(problem: unknown): Problem {
  return isProblem(problem) ? toProblem(problem) : { title: GENERIC_REASON_MESSAGE };
}
