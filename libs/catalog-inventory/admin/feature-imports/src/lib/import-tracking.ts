import {
  computed,
  DestroyRef,
  DOCUMENT,
  effect,
  inject,
  type Signal,
  signal,
  untracked,
} from '@angular/core';
import {
  getProductImport,
  type ImportResponse,
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  apiData,
  type Problem,
  type ResponseHeaders,
  toProblem,
} from '@marketplace/shared-web-util';

import { isImportRunning } from './import-presentation';

// WHY: cópia pendente (ADR 031); saída: Retry-After em GET /products/imports/{id} enquanto o lote está em andamento.
export const POLL_INTERVAL_MS = 2000;

/**
 * Espera pedida pelo `Retry-After` da resposta, em milissegundos: segundos inteiros ou data HTTP. Sem o cabeçalho, ou
 * com valor que não se lê, devolve `undefined`. Aceita os cabeçalhos ou a resposta que os carrega.
 */
export function retryAfterMs(source: unknown, now: number = Date.now()): number | undefined {
  const value = headersOf(source)?.get('Retry-After')?.trim();
  if (!value) {
    return undefined;
  }
  if (/^\d+$/.test(value)) {
    return Number(value) * 1000;
  }
  const date = Date.parse(value);
  return Number.isFinite(date) ? Math.max(0, date - now) : undefined;
}

/** Cabeçalhos de uma resposta do cliente gerado (`HttpResponse`) ou os próprios cabeçalhos. */
export function headersOf(source: unknown): ResponseHeaders | undefined {
  if (hasGet(source)) {
    return source;
  }
  return isRecord(source) && hasGet(source['headers']) ? source['headers'] : undefined;
}

/** Acompanhamento de um lote: a última leitura, o problema da última consulta e o carregamento em curso. */
export interface ImportTracking {
  /** Última leitura; as linhas (`lines`) só vêm com o lote no status terminal. */
  readonly report: Signal<ImportResponse | undefined>;
  /** Problema da última consulta; a última leitura continua em `report`. */
  readonly problem: Signal<Problem | null>;
  readonly loading: Signal<boolean>;
  /** O lote está em andamento e o painel continua consultando. */
  readonly running: Signal<boolean>;
  /** Consulta de novo, sem apagar a última leitura, e retoma o acompanhamento se o lote seguir em andamento. */
  retry(): void;
}

/**
 * Lê o andamento do lote em `GET /products/imports/{id}?lines=false` e repete a leitura enquanto ele está em
 * andamento, no intervalo do `Retry-After` da resposta ou, sem ele, a cada `POLL_INTERVAL_MS`; com a aba oculta, a
 * próxima leitura espera a aba voltar. No status terminal, lê o lote uma vez com as linhas e para. Também para na
 * falha (até `retry`), ao trocar de lote e ao destruir quem chamou, como ao sair da tela. Chame num contexto de injeção.
 */
export function trackImport(id: () => string | null | undefined): ImportTracking {
  const document = inject(DOCUMENT);
  const report = signal<ImportResponse | undefined>(undefined);
  const problem = signal<Problem | null>(null);
  const loading = signal(false);
  let generation = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  /** Lote cuja próxima leitura chegou com a aba oculta e espera ela voltar. */
  let paused: string | undefined;

  const stop = () => {
    generation++;
    clearTimeout(timer);
    timer = undefined;
    paused = undefined;
  };

  const schedule = (target: string, run: number, delay: number) => {
    timer = setTimeout(() => {
      timer = undefined;
      if (document.hidden) {
        paused = target;
      } else {
        void load(target, run);
      }
    }, delay);
  };

  const resume = () => {
    if (!document.hidden && paused) {
      const target = paused;
      paused = undefined;
      void load(target, generation);
    }
  };

  const load = async (target: string, run: number) => {
    loading.set(true);
    try {
      const result = await getProductImport({ path: { id: target }, query: { lines: false } });
      const progress = await apiData(Promise.resolve(result));
      if (run !== generation) {
        return;
      }
      if (isImportRunning(progress.status)) {
        report.set(progress);
        problem.set(null);
        schedule(target, run, retryAfterMs(result.response) ?? POLL_INTERVAL_MS);
        return;
      }
      const done = await apiData(getProductImport({ path: { id: target } }));
      if (run === generation) {
        report.set(done);
        problem.set(null);
      }
    } catch (error) {
      if (run === generation) {
        problem.set(toProblem(error));
      }
    } finally {
      if (run === generation) {
        loading.set(false);
      }
    }
  };

  const start = (target: string | null | undefined) => {
    stop();
    if (target) {
      void load(target, generation);
    } else {
      loading.set(false);
    }
  };

  effect(() => {
    const target = id();
    untracked(() => {
      report.set(undefined);
      problem.set(null);
      start(target);
    });
  });
  document.addEventListener('visibilitychange', resume);
  inject(DestroyRef).onDestroy(() => {
    stop();
    document.removeEventListener('visibilitychange', resume);
  });

  return {
    report: report.asReadonly(),
    problem: problem.asReadonly(),
    loading: loading.asReadonly(),
    running: computed(() => isImportRunning(report()?.status)),
    retry: () => start(untracked(id)),
  };
}

function hasGet(value: unknown): value is ResponseHeaders {
  return isRecord(value) && typeof value['get'] === 'function';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
