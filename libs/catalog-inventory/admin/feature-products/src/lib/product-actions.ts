import {
  cilInbox,
  cilLockUnlocked,
  cilMediaPause,
  cilMediaPlay,
  cilPencil,
  cilSend,
} from '@coreui/icons';
import type {
  ProductBatchAction,
  ProductResponse,
} from '@marketplace/catalog-inventory-admin-data-access';
import type { ActionPresentation, BatchActionPresentation } from '@marketplace/shared-admin-ui';
import { actionState, type BatchVerb } from '@marketplace/shared-web-util';

/**
 * Ação com lugar no menu "...", no botão principal ou na barra de lote, pelo código que o servidor devolve em
 * `actions`. O servidor decide o que vale para o produto e o chamador; aqui fica só a apresentação.
 */
export type ProductMenuAction = 'EDIT' | 'SUBMIT' | 'PAUSE' | 'RESUME' | 'ARCHIVE' | 'UNBLOCK';

/**
 * Onde fica o menu "...": na linha de Produtos da loja (`list`), na tela do produto (`detail`) ou na linha de
 * Produtos de todas as lojas (`platform`).
 */
export type ProductActionPlace = 'list' | 'detail' | 'platform';

export interface ProductActionConfirmation {
  readonly title: string;
  /** Efeito da ação num item; a tela mostra junto o nome do produto. */
  readonly message: string;
  /** Efeito da ação em lote. */
  readonly batchMessage: string;
  readonly confirmLabel: string;
  readonly color: 'primary' | 'danger' | 'warning';
}

/** Apresentação de uma ação: texto, ícone, cor, confirmação e onde ela aparece. */
export interface ProductActionPresentation {
  readonly code: ProductMenuAction;
  /** Nome no menu, no botão e na barra de lote ("Pausar"). */
  readonly label: string;
  /** Nome mais curto na barra de lote, quando o do menu não cabe ("Enviar"). */
  readonly batchLabel?: string;
  /** Rótulo durante a chamada ("Pausando"). */
  readonly runningLabel: string;
  /** Particípio para o resumo do lote ("6 pausados"). */
  readonly done: BatchVerb;
  /** Toast da ação num item só. */
  readonly successMessage: string;
  /** Cor do botão na barra de lote. */
  readonly color: 'primary' | 'danger';
  readonly icon: string[];
  /** Lugares do menu em que a ação aparece. */
  readonly places: readonly ProductActionPlace[];
  /** Ações que pedem modal antes de chamar a API; as outras só confirmam pelo toast. */
  readonly confirmation?: ProductActionConfirmation;
}

const EVERYWHERE: readonly ProductActionPlace[] = ['list', 'detail'];

/**
 * Apresentação das ações, na ordem dos menus e da barra de lote (proposta de design, seção "4. Produto"). A lista, a
 * tela do produto e a barra de lote usam esta tabela, para que os menus tenham os mesmos nomes e a mesma ordem.
 */
export const PRODUCT_ACTIONS: readonly ProductActionPresentation[] = [
  {
    code: 'EDIT',
    label: 'Editar',
    runningLabel: 'Abrindo',
    done: { one: 'aberto', many: 'abertos' },
    successMessage: '',
    color: 'primary',
    icon: cilPencil,
    // WHY: a tela do produto já é a edição, então o Editar só aparece na lista.
    places: ['list'],
  },
  {
    code: 'SUBMIT',
    label: 'Enviar para moderação',
    batchLabel: 'Enviar',
    runningLabel: 'Enviando',
    done: { one: 'enviado para moderação', many: 'enviados para moderação' },
    successMessage: 'Produto enviado para moderação.',
    color: 'primary',
    icon: cilSend,
    places: EVERYWHERE,
  },
  {
    code: 'PAUSE',
    label: 'Pausar',
    runningLabel: 'Pausando',
    done: { one: 'pausado', many: 'pausados' },
    successMessage: 'Produto pausado. Ele sai da vitrine até você reativar.',
    color: 'primary',
    icon: cilMediaPause,
    places: EVERYWHERE,
  },
  {
    code: 'RESUME',
    label: 'Reativar',
    runningLabel: 'Reativando',
    done: { one: 'reativado', many: 'reativados' },
    successMessage: 'Produto reativado.',
    color: 'primary',
    icon: cilMediaPlay,
    places: EVERYWHERE,
  },
  {
    code: 'ARCHIVE',
    label: 'Arquivar',
    runningLabel: 'Arquivando',
    done: { one: 'arquivado', many: 'arquivados' },
    successMessage: 'Produto arquivado.',
    color: 'danger',
    icon: cilInbox,
    places: EVERYWHERE,
    confirmation: {
      title: 'Arquivar produto',
      message:
        'O produto sai da vitrine na hora e fica só para consulta. Arquivar não pode ser desfeito.',
      batchMessage:
        'Os produtos saem da vitrine na hora e ficam só para consulta. Arquivar não pode ser desfeito.',
      confirmLabel: 'Arquivar',
      color: 'danger',
    },
  },
  {
    code: 'UNBLOCK',
    label: 'Desbloquear',
    runningLabel: 'Desbloqueando',
    done: { one: 'desbloqueado', many: 'desbloqueados' },
    successMessage: 'Produto desbloqueado. Ele volta para a fila de moderação.',
    color: 'primary',
    icon: cilLockUnlocked,
    places: ['detail', 'platform'],
  },
];

/** Ações da barra de lote de Produtos da loja, as que o endpoint de lote aceita. */
export const BATCH_PRODUCT_ACTIONS: readonly (ProductMenuAction & ProductBatchAction)[] = [
  'SUBMIT',
  'PAUSE',
  'RESUME',
  'ARCHIVE',
];

/** Ações que também viram botão na tela do produto; vale a primeira que o servidor oferece. */
const PRIMARY_CANDIDATES: readonly ProductMenuAction[] = ['SUBMIT', 'PAUSE', 'RESUME'];

/** Texto da confirmação ao salvar produto publicado (`saveReturnsToModeration`). */
export const SAVE_RETURNS_TO_MODERATION: Omit<ProductActionConfirmation, 'batchMessage'> = {
  title: 'Salvar e devolver à moderação',
  message: 'Salvar devolve o produto à moderação, e ele sai da vitrine até ser aprovado.',
  confirmLabel: 'Salvar',
  color: 'warning',
};

/** O que o servidor respondeu sobre as ações do produto para o chamador. */
export type ProductActionsSource = Pick<ProductResponse, 'actions' | 'blockedActions'>;

/** Situação de uma ação no produto, pelo que o servidor respondeu. */
export interface ProductActionState {
  /** Pode ser executada agora (`actions`). */
  readonly allowed: boolean;
  /** Vale no status, mas está barrada: a tela mostra desabilitada com este texto do servidor (`blockedActions`). */
  readonly blockedReason?: string;
}

/** Item do menu: a apresentação e, quando barrada, o motivo para mostrar a ação desabilitada. */
export interface ProductMenuItem {
  readonly action: ProductActionPresentation;
  readonly blockedReason?: string;
}

/** Situação da ação no produto (`actionState`); ação ausente das duas listas não vale e não aparece. */
export function productActionState(
  product: ProductActionsSource,
  code: string,
): ProductActionState {
  return actionState(product, code);
}

/** Apresentação do código que o servidor devolve; `undefined` para o código que o painel não conhece. */
export function productActionPresentation(code: string): ProductActionPresentation | undefined {
  return PRODUCT_ACTIONS.find((action) => action.code === code);
}

/**
 * Apresentações do lugar para o `app-action-menu` (`serverActionMenu`), na ordem da tabela, com as destrutivas em
 * vermelho.
 */
export function productActionPresentations(place: ProductActionPlace): ActionPresentation[] {
  return PRODUCT_ACTIONS.filter((action) => action.places.includes(place)).map((action) => ({
    id: action.code,
    label: action.label,
    icon: action.icon,
    danger: action.color === 'danger',
  }));
}

/** Botões da barra de lote de Produtos da loja, na ordem da barra; a contagem vem da seleção (`ListSelection`). */
export const PRODUCT_BATCH_PRESENTATIONS: readonly BatchActionPresentation[] =
  BATCH_PRODUCT_ACTIONS.map((code) => {
    const action = productAction(code);
    return {
      id: code,
      label: action.batchLabel ?? action.label,
      runningLabel: action.runningLabel,
      color: action.color,
    };
  });

/**
 * Itens do menu "..." do produto, na ordem da tabela: as ações liberadas e as barradas, estas desabilitadas com o
 * motivo do servidor. Código que o painel não conhece fica de fora.
 */
export function productMenu(
  product: ProductActionsSource,
  place: ProductActionPlace,
): ProductMenuItem[] {
  return PRODUCT_ACTIONS.filter((action) => action.places.includes(place)).flatMap((action) => {
    const item = menuItem(product, action);
    return item ? [item] : [];
  });
}

/** Ação principal, mostrada como botão ao lado do menu na tela do produto: a primeira que o servidor oferece. */
export function primaryProductAction(product: ProductActionsSource): ProductMenuItem | null {
  for (const code of PRIMARY_CANDIDATES) {
    const item = menuItem(product, productAction(code));
    if (item) {
      return item;
    }
  }
  return null;
}

/** Quantos dos selecionados o servidor libera para cada ação da barra de lote, na ordem da barra. */
export function batchActionCounts(
  products: readonly ProductActionsSource[],
  actions: readonly ProductMenuAction[] = BATCH_PRODUCT_ACTIONS,
): { readonly action: ProductActionPresentation; readonly count: number }[] {
  return actions.map((code) => ({
    action: productAction(code),
    count: products.filter((product) => product.actions?.includes(code)).length,
  }));
}

/** O que a tela do produto libera além do menu, pelo que o servidor respondeu. */
export interface ProductEditRules {
  /** Salvar conteúdo e SKU (`EDIT`). */
  readonly save: ProductActionState;
  readonly changeCategory: ProductActionState;
  readonly addSku: ProductActionState;
  readonly removeSku: ProductActionState;
  /** Preço e promoções no painel do SKU. */
  readonly editOffer: ProductActionState;
  readonly editStock: ProductActionState;
  /** Salvar devolve o produto publicado à moderação, com a confirmação `SAVE_RETURNS_TO_MODERATION`. */
  readonly saveReturnsToModeration: boolean;
  /** Mostra o checklist de envio quando o servidor oferece o envio, liberado ou barrado. */
  readonly showChecklist: boolean;
}

/** Regras da tela do produto, tiradas de `actions`, `blockedActions` e `saveReturnsToModeration`. */
export function productEditRules(
  product: ProductActionsSource & Pick<ProductResponse, 'saveReturnsToModeration'>,
): ProductEditRules {
  const submit = productActionState(product, 'SUBMIT');
  return {
    save: productActionState(product, 'EDIT'),
    changeCategory: productActionState(product, 'CHANGE_CATEGORY'),
    addSku: productActionState(product, 'ADD_SKU'),
    removeSku: productActionState(product, 'REMOVE_SKU'),
    editOffer: productActionState(product, 'EDIT_OFFER'),
    editStock: productActionState(product, 'EDIT_STOCK'),
    saveReturnsToModeration: product.saveReturnsToModeration === true,
    showChecklist: submit.allowed || submit.blockedReason !== undefined,
  };
}

function productAction(code: ProductMenuAction): ProductActionPresentation {
  return PRODUCT_ACTIONS.find((action) => action.code === code) as ProductActionPresentation;
}

function menuItem(
  product: ProductActionsSource,
  action: ProductActionPresentation,
): ProductMenuItem | null {
  const state = productActionState(product, action.code);
  if (state.allowed) {
    return { action };
  }
  return state.blockedReason === undefined ? null : { action, blockedReason: state.blockedReason };
}
