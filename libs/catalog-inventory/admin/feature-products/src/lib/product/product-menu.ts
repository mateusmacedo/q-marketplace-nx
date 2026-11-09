import {
  archiveProduct,
  type ProductResponse,
  pauseProduct,
  resumeProduct,
  submitProduct,
  unblockProduct
} from '@marketplace/catalog-inventory-admin-data-access';
import type { PublishingIssue } from '@marketplace/catalog-inventory-admin-ui';
import type { ActionMenuItem } from '@marketplace/shared-admin-ui';
import {
  apiDataWithVersion,
  type Problem,
  problemReasons,
  type Versioned
} from '@marketplace/shared-web-util';

import { PRODUCT_ACTIONS, type ProductActionPresentation, type ProductMenuAction, type ProductMenuItem } from '../product-actions';

/** Itens do `app-action-menu` a partir do menu do produto: as barradas vão desabilitadas, com o motivo no tooltip. */
export function actionMenuItems(items: readonly ProductMenuItem[]): ActionMenuItem[] {
  return items.map(item => ({
    id: item.action.code,
    label: item.action.label,
    icon: item.action.icon,
    danger: item.action.color === 'danger',
    ...(item.blockedReason === undefined ? {} : { disabledReason: item.blockedReason })
  }));
}

/** Apresentação da ação pelo código escolhido no menu; código que o painel não apresenta devolve `undefined`. */
export function productActionPresentation(code: string): ProductActionPresentation | undefined {
  return PRODUCT_ACTIONS.find(action => action.code === code);
}

/** Frase antes das mensagens do servidor quando a ação falha: "Não foi possível pausar". */
export function actionFailureHeading(action: ProductActionPresentation): string {
  return `Não foi possível ${action.label.charAt(0).toLowerCase()}${action.label.slice(1)}`;
}

/**
 * Chama o endpoint da ação no produto, com o `If-Match` da versão lida, e devolve o produto com a versão nova. `EDIT`
 * não tem chamada: na tela do produto, o conteúdo já é a edição.
 */
export function runProductAction(
  code: ProductMenuAction,
  id: string,
  version: string | undefined
): Promise<Versioned<ProductResponse>> {
  const options = { path: { id }, ...(version ? { headers: { 'If-Match': version } } : {}) };
  switch (code) {
    case 'SUBMIT':
      return apiDataWithVersion(submitProduct(options));
    case 'PAUSE':
      return apiDataWithVersion(pauseProduct(options));
    case 'RESUME':
      return apiDataWithVersion(resumeProduct(options));
    case 'ARCHIVE':
      return apiDataWithVersion(archiveProduct(options));
    case 'UNBLOCK':
      return apiDataWithVersion(unblockProduct(options));
    case 'EDIT':
      return Promise.reject(new Error('A edição não tem chamada própria na tela do produto'));
  }
}

/**
 * Motivos da recusa do envio (422) para o checklist, com o campo de cada um tirado da pendência do produto que tem o
 * mesmo código e os mesmos valores, para levar à aba e ao campo do problema.
 */
export function refusalIssues(problem: Problem, known: readonly PublishingIssue[] | null | undefined): PublishingIssue[] {
  return problemReasons(problem).map(reason => {
    const match = (known ?? []).find(
      issue => issue.code === reason.code && sameParams(issue.params ?? [], reason.params ?? [])
    );
    return { code: reason.code, params: reason.params, message: reason.message ?? match?.message, field: match?.field };
  });
}

function sameParams(left: readonly string[], right: readonly string[]): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}
