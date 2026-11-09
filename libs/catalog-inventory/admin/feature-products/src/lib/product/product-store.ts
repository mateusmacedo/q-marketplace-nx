import { computed, Injectable, signal } from '@angular/core';
import {
  getProduct,
  type ProductCheckRequest,
  type ProductResponse
} from '@marketplace/catalog-inventory-admin-data-access';
import type { ChecklistItem, PublishingIssue } from '@marketplace/catalog-inventory-admin-ui';
import {
  apiDataWithVersion,
  type Problem,
  toProblem,
  type Versioned,
  versionTag
} from '@marketplace/shared-web-util';

import { productEditRules, type ProductEditRules } from '../product-actions';

/** Regras de um produto que ainda não chegou: nada liberado. */
const NOTHING_ALLOWED: ProductEditRules = productEditRules({});

/** Cabeçalho `If-Match` com a versão lida; sem versão, a gravação sai sem ele e o servidor decide. */
export function ifMatch(version: string | undefined): { 'If-Match': string } | undefined {
  return version ? { 'If-Match': version } : undefined;
}

/**
 * Produto aberto na tela, compartilhado pelo cabeçalho, pelas abas e pelo painel do SKU. Guarda a resposta do servidor
 * e a versão do `ETag` para o `If-Match` das gravações. A tela que o fornece (`providers`) carrega pelo id da rota.
 */
@Injectable()
export class ProductStore {
  readonly #product = signal<ProductResponse | undefined>(undefined);
  readonly #version = signal<string | undefined>(undefined);
  readonly #loading = signal(false);
  readonly #problem = signal<Problem | undefined>(undefined);
  readonly #discards = signal(0);
  #id: string | undefined;
  #request = 0;

  readonly product = this.#product.asReadonly();
  /** ETag da última leitura ou gravação do produto. */
  readonly version = this.#version.asReadonly();
  readonly loading = this.#loading.asReadonly();
  /** Falha da última leitura. */
  readonly problem = this.#problem.asReadonly();
  /** Muda quando a pessoa pede a versão atual: o formulário de conteúdo descarta o que foi digitado. */
  readonly discards = this.#discards.asReadonly();
  /** O que o servidor libera no produto, por `actions`, `blockedActions` e `saveReturnsToModeration`. */
  readonly rules = computed(() => {
    const product = this.#product();
    return product ? productEditRules(product) : NOTHING_ALLOWED;
  });

  /** Rascunho do conteúdo não salvo, conferido pelo checklist de envio; vazio quando o formulário é igual ao salvo. */
  readonly draft = signal<ProductCheckRequest | undefined>(undefined);
  /** Pendência escolhida no checklist, que a aba de destino atende pondo o foco no campo. */
  readonly focus = signal<ChecklistItem | null>(null);
  /** Motivos da última recusa do envio (422), que o checklist mostra até o produto ou o rascunho mudar. */
  readonly refusal = signal<PublishingIssue[] | null>(null);

  /** Lê o produto. Devolve `false` quando a leitura falhou ou foi trocada por outra leitura ou gravação mais nova. */
  async load(id: string): Promise<boolean> {
    const request = ++this.#request;
    if (id !== this.#id) {
      this.#id = id;
      this.#product.set(undefined);
      this.#version.set(undefined);
      this.draft.set(undefined);
      this.focus.set(null);
    }
    this.#loading.set(true);
    this.#problem.set(undefined);
    try {
      const result = await apiDataWithVersion(getProduct({ path: { id } }));
      if (request === this.#request) {
        this.#apply(result);
      }
      return request === this.#request;
    } catch (error) {
      if (request === this.#request) {
        this.#problem.set(toProblem(error));
      }
      return false;
    } finally {
      if (request === this.#request) {
        this.#loading.set(false);
      }
    }
  }

  /**
   * Lê de novo o produto aberto. Com `discard`, como depois do conflito de edição, o formulário de conteúdo troca o que
   * foi digitado pela versão atual.
   */
  async reload(discard = false): Promise<boolean> {
    const id = this.#id;
    if (!id) {
      return false;
    }
    const loaded = await this.load(id);
    if (loaded && discard) {
      this.#discards.update(count => count + 1);
    }
    return loaded;
  }

  /**
   * Guarda o produto que uma gravação devolveu, com a versão nova. A leitura que ainda está em andamento é descartada,
   * porque começou antes da gravação e traria o produto e a versão anteriores. Sem `actions`, o servidor gravou, mas não
   * conseguiu ler o resto depois (ofertas, pendências, situação da loja), e o produto é lido de novo para completar a tela.
   */
  accept(result: Versioned<ProductResponse>): void {
    this.#request++;
    this.#loading.set(false);
    this.#apply(result);
    if (result.data.actions == null) {
      void this.reload();
    }
  }

  /** A recusa do envio sai, porque as pendências do produto novo valem mais que ela. */
  #apply(result: Versioned<ProductResponse>): void {
    this.#id ??= result.data.id;
    this.refusal.set(null);
    this.#product.set(result.data);
    this.#version.set(result.version ?? versionTag(result.data.version));
  }
}
