import { computed, linkedSignal, type Signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import {
  type CategoryChangeResponse,
  checkProductResource,
  type ProductCheckRequest,
  type ProductCheckResponse,
  type ProductResponse,
  type PublishingIssueResponse,
} from '@marketplace/catalog-inventory-admin-data-access';
import { type Problem, toProblem } from '@marketplace/shared-web-util';
import { debounceTime } from 'rxjs';

/** Espera depois da última alteração do formulário antes de conferir o rascunho no servidor. */
export const DRAFT_CHECK_DELAY_MS = 400;

/** Conferência do rascunho do produto, para o checklist de envio e a prévia da troca de categoria. */
export interface ProductDraftCheck {
  /** Pendências do rascunho conferido ou, sem rascunho, as do produto salvo. */
  readonly issues: Signal<readonly PublishingIssueResponse[] | null | undefined>;
  /** Efeito da troca de categoria do rascunho; nulo quando a categoria não muda. */
  readonly categoryChange: Signal<CategoryChangeResponse | null | undefined>;
  /** Esperando o fim da digitação ou a resposta do servidor. */
  readonly loading: Signal<boolean>;
  readonly problem: Signal<Problem | undefined>;
  /** Confere de novo o mesmo rascunho, depois de uma falha. */
  reload(): void;
}

interface CheckRequest {
  readonly id: string;
  readonly body: ProductCheckRequest;
}

type CheckedProduct = Pick<ProductResponse, 'id' | 'publishingIssues'>;

/**
 * Confere o rascunho no servidor (`POST /products/{id}/check`), que não grava nada e responde as pendências e o
 * efeito da troca de categoria com as regras do domínio. Sem rascunho (`draft` vazio, formulário igual ao salvo), valem
 * as pendências do produto (`publishingIssues`) e não há chamada. A chamada sai `delay` milissegundos depois da última
 * alteração, e a resposta anterior continua na tela até a nova chegar.
 */
export function productDraftCheck(
  product: () => CheckedProduct | null | undefined,
  draft: () => ProductCheckRequest | null | undefined,
  delay = DRAFT_CHECK_DELAY_MS,
): ProductDraftCheck {
  const request = computed<CheckRequest | undefined>(
    () => {
      const id = product()?.id;
      const body = draft();
      return id && body ? { id, body } : undefined;
    },
    { equal: sameRequest },
  );
  const settled = toSignal(toObservable(request).pipe(debounceTime(delay)), {
    initialValue: undefined,
  });
  const check = checkProductResource(() => {
    const current = settled();
    return current ? { path: { id: current.id }, body: current.body } : undefined;
  });
  const last = linkedSignal<ProductCheckResponse | undefined, ProductCheckResponse | undefined>({
    source: () => (check.hasValue() ? check.value() : undefined),
    computation: (next, previous) => next ?? previous?.value,
  });
  const active = computed(() => request() !== undefined);

  return {
    issues: computed(() => (active() ? last()?.publishingIssues : product()?.publishingIssues)),
    categoryChange: computed(() => (active() ? last()?.categoryChange : null)),
    loading: computed(() => active() && (!sameRequest(request(), settled()) || check.isLoading())),
    problem: computed(() => {
      const error = check.error();
      return active() && error ? toProblem(error) : undefined;
    }),
    reload: () => {
      check.reload();
    },
  };
}

function sameRequest(left: CheckRequest | undefined, right: CheckRequest | undefined): boolean {
  if (!left || !right) {
    return left === right;
  }
  return left.id === right.id && JSON.stringify(left.body) === JSON.stringify(right.body);
}
