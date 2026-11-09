import { computed, inject, type Signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';

import { type ProductListQuery, readProductListQuery } from './product-list-query';

/** Mudança no recorte da URL; `null` tira o parâmetro. */
export type ProductListChanges = Readonly<Partial<Record<keyof ProductListQuery, string | number | null>>>;

/** Recorte da lista lido da URL e a navegação que o muda, para as duas listas de produtos. */
export interface ProductListRoute {
  readonly query: Signal<ProductListQuery>;
  /** Junta as mudanças aos parâmetros da URL; o botão voltar do navegador refaz a lista. */
  navigate(changes: ProductListChanges): void;
  /** Abre um caminho a partir da rota da lista, como o produto (`[id, 'conteudo']`). */
  open(commands: readonly unknown[]): void;
}

/** Liga a lista à URL (`?status=&q=&sort=&limit=&cursor=`). Chame no contexto de injeção da tela. */
export function injectProductListRoute(): ProductListRoute {
  const route = inject(ActivatedRoute);
  const router = inject(Router);
  const params = toSignal(route.queryParamMap, { requireSync: true });
  return {
    query: computed(() => readProductListQuery(params())),
    navigate: changes => {
      void router.navigate([], { relativeTo: route, queryParams: changes, queryParamsHandling: 'merge' });
    },
    open: commands => {
      void router.navigate(commands, { relativeTo: route });
    }
  };
}
