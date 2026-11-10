import { DestroyRef, inject } from '@angular/core';
import { type BatchRunFailure, problemMessages } from '@marketplace/shared-web-util';

import type { BatchFailure } from './batch-result-alert.component';

/** Falhas do lote para o `app-batch-result-alert`, com o nome que a pessoa reconhece e as mensagens do servidor. */
export function batchFailures<T>(
  failures: readonly BatchRunFailure<T>[],
  id: (item: T) => string,
  label: (item: T) => string,
): BatchFailure[] {
  return failures.map((failure) => ({
    id: id(failure.item),
    label: label(failure.item),
    messages: problemMessages(failure.problem),
  }));
}

/**
 * Sinal abortado quando a tela é destruída, para o `runBatch` não enviar os grupos seguintes depois da saída. Chame
 * no contexto de injeção da tela, como o construtor.
 */
export function abortOnDestroy(): AbortSignal {
  const controller = new AbortController();
  inject(DestroyRef).onDestroy(() => controller.abort());
  return controller.signal;
}
