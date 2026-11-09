import { inject, signal } from '@angular/core';
import {
  archiveProduct,
  pauseProduct,
  type ProductResponse,
  resumeProduct,
  submitProduct,
  unblockProduct
} from '@marketplace/catalog-inventory-admin-data-access';
import { ToastService } from '@marketplace/shared-admin-ui';
import { apiData, isEditConflict, type Problem, toProblem, versionTag } from '@marketplace/shared-web-util';

import type { ProductActionPresentation, ProductMenuAction } from '../product-actions';

/** Produto da linha: o id e a versão lida, que vai no `If-Match`, e o título para as mensagens. */
export type ProductRow = Pick<ProductResponse, 'id' | 'version' | 'title'>;

/** Recusa de uma ação de um item, com o título do alerta. */
export interface ProductActionFailure {
  readonly heading: string;
  readonly problem: Problem;
}

/**
 * Chama o endpoint da ação de um item com o `If-Match` da versão lida na lista. `EDIT` não é chamada: a tela abre o
 * produto.
 */
export function sendProductAction(code: ProductMenuAction, product: ProductRow): Promise<ProductResponse> {
  const options = { path: { id: product.id ?? '' }, headers: { 'If-Match': versionTag(product.version) } };
  switch (code) {
    case 'SUBMIT':
      return apiData(submitProduct(options));
    case 'PAUSE':
      return apiData(pauseProduct(options));
    case 'RESUME':
      return apiData(resumeProduct(options));
    case 'ARCHIVE':
      return apiData(archiveProduct(options));
    case 'UNBLOCK':
      return apiData(unblockProduct(options));
    case 'EDIT':
      return Promise.reject(new Error('Editar abre o produto e não chama a API'));
  }
}

/**
 * Executa as ações de um item da lista: o toast no sucesso, o aviso de conflito no 412 e no 409 `concurrent-update`
 * e o alerta com as mensagens do servidor nas outras recusas. Crie no contexto de injeção da tela.
 */
export class ProductActionRunner {
  readonly #toast = inject(ToastService);

  /** Produto com a ação em andamento; o menu dele fica travado. */
  readonly busyId = signal<string | null>(null);
  /** A versão da lista ficou para trás: a tela mostra o aviso de conflito com "Carregar versão atual". */
  readonly conflict = signal(false);
  readonly failure = signal<ProductActionFailure | null>(null);

  /** Executa a ação e devolve `true` quando o servidor gravou; a tela recarrega a lista. */
  async run(product: ProductRow, action: ProductActionPresentation): Promise<boolean> {
    this.busyId.set(product.id ?? null);
    this.dismiss();
    try {
      await sendProductAction(action.code, product);
      this.#toast.success(action.successMessage);
      return true;
    } catch (error) {
      const problem = toProblem(error);
      if (isEditConflict(problem)) {
        this.conflict.set(true);
      } else {
        this.failure.set({ heading: `Não foi possível ${action.label.toLowerCase()} “${product.title ?? ''}”`, problem });
      }
      return false;
    } finally {
      this.busyId.set(null);
    }
  }

  /** Fecha o aviso de conflito e o alerta da última ação. */
  dismiss(): void {
    this.conflict.set(false);
    this.failure.set(null);
  }
}
