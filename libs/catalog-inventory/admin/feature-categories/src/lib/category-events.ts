import { Injectable, signal } from '@angular/core';

/** Categoria recém-criada que a lista deve mostrar: o nível do pai, com o detalhe dela aberto. */
export interface CreatedCategory {
  /** Pai da categoria criada; `null` na raiz. */
  readonly parentId: string | null;
  /** Id que o `POST /categories` devolveu; sem ele, a lista abre só o nível do pai. */
  readonly id: string | undefined;
}

/**
 * Avisos entre a lista por nível e o detalhe ao lado, que são rotas diferentes da mesma tela. Fornecido na rota de
 * Categorias, vive enquanto a tela está aberta.
 */
@Injectable()
export class CategoryEvents {
  readonly #listStamp = signal(0);
  readonly #detailStamp = signal(0);
  readonly #created = signal<CreatedCategory | null>(null);
  readonly #gone = signal<{ readonly id: string } | null>(null);

  /** Muda quando uma gravação no detalhe pede para a lista, o breadcrumb e os números serem lidos de novo. */
  readonly listStamp = this.#listStamp.asReadonly();
  /** Muda quando um lote pede para o detalhe aberto ser lido de novo. */
  readonly detailStamp = this.#detailStamp.asReadonly();
  /** Categoria criada à espera de ser mostrada pela lista. */
  readonly created = this.#created.asReadonly();
  /** Categoria aberta no detalhe, que a lista destaca. */
  readonly openId = signal<string | null>(null);
  /**
   * Última categoria que o detalhe excluiu ou moveu de nível; a seleção do lote a solta, porque guardaria a versão e
   * o pai antigos.
   */
  readonly gone = this.#gone.asReadonly();

  listChanged(): void {
    this.#listStamp.update(stamp => stamp + 1);
  }

  detailChanged(): void {
    this.#detailStamp.update(stamp => stamp + 1);
  }

  left(id: string): void {
    this.#gone.set({ id });
  }

  reveal(created: CreatedCategory): void {
    this.#created.set(created);
  }

  /** A lista leu a categoria criada e devolve o pedido atendido. */
  takeCreated(): CreatedCategory | null {
    const created = this.#created();
    this.#created.set(null);
    return created;
  }
}
