import { Injectable, signal } from '@angular/core';

/** Tempo em que a última leitura de um badge é reaproveitada por `refresh`, em milissegundos. */
export const NAV_BADGE_MAX_AGE_MS = 60_000;

/** Lê a contagem de um badge; zero, `null` ou `undefined` escondem o badge. */
export type NavBadgeLoader = () => Promise<number | null | undefined>;

export interface NavBadgeRefreshOptions {
  /** Lê de novo mesmo dentro da janela, como depois de uma ação que muda a contagem. */
  readonly force?: boolean;
}

interface BadgeLoad {
  /** Instante, em `Date.now()`, da última leitura que respondeu. */
  loadedAt?: number;
  pending?: Promise<void>;
  /** Número da leitura mais recente, para a resposta atrasada de uma anterior não sobrescrever a contagem. */
  request: number;
}

/**
 * Contagens dos badges do menu lateral, por chave. O item do menu declara a chave (`badgeKey`), e a feature dona do
 * número a alimenta, como a contagem da fila de moderação. Contagem zero ou ausente esconde o badge.
 */
@Injectable({ providedIn: 'root' })
export class NavBadgeService {
  readonly #counts = signal<Readonly<Record<string, number>>>({});
  readonly counts = this.#counts.asReadonly();
  readonly #loads = new Map<string, BadgeLoad>();

  set(key: string, count: number | null | undefined): void {
    this.#counts.update((counts) => {
      const next = { ...counts };
      if (count) {
        next[key] = count;
      } else {
        delete next[key];
      }
      return next;
    });
  }

  /**
   * Atualiza a contagem de `key` com `load`, reaproveitando a última leitura por `NAV_BADGE_MAX_AGE_MS` e a leitura em
   * andamento; `force` lê de novo mesmo assim. Na falha, o badge continua como estava e a próxima chamada lê de novo.
   */
  refresh(key: string, load: NavBadgeLoader, options: NavBadgeRefreshOptions = {}): Promise<void> {
    const state = this.#loads.get(key) ?? { request: 0 };
    this.#loads.set(key, state);
    if (!options.force) {
      if (state.pending) {
        return state.pending;
      }
      if (state.loadedAt !== undefined && Date.now() - state.loadedAt < NAV_BADGE_MAX_AGE_MS) {
        return Promise.resolve();
      }
    }
    const request = ++state.request;
    const pending = load()
      .then(
        (count) => {
          if (request === state.request) {
            this.set(key, count);
            state.loadedAt = Date.now();
          }
        },
        () => undefined,
      )
      .finally(() => {
        if (state.pending === pending) {
          state.pending = undefined;
        }
      });
    state.pending = pending;
    return pending;
  }
}
