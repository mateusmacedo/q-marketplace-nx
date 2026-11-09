import { computed, linkedSignal, type Signal } from '@angular/core';
import {
  type CategoryStatsResponse,
  getCategoryStatsResource,
  getModerationStatsResource,
  getStoreStatsResource,
  type ModerationStatsResponse,
  type StoreStatsResponse
} from '@marketplace/catalog-inventory-admin-data-access';
import type { AdminSession } from '@marketplace/shared-admin-ui';
import { type Problem, toProblem } from '@marketplace/shared-web-util';

import type { ActivityGranularity } from './home-activity';

/** Números da página inicial, do conjunto que a sessão vê. */
export type HomeStatsValue =
  | { readonly set: 'store'; readonly stats: StoreStatsResponse }
  | { readonly set: 'moderation'; readonly stats: ModerationStatsResponse }
  | { readonly set: 'categories'; readonly stats: CategoryStatsResponse };

export type HomeStatsSet = HomeStatsValue['set'];

/**
 * Conjunto de indicadores pela capacidade da sessão (proposta de design, seção "1. Início"): a loja com
 * `MANAGE_OWN_PRODUCTS`; sem ela, a moderação com `MODERATE_PRODUCTS`; e, sem as duas, a árvore com `MANAGE_TAXONOMY`.
 */
export function homeStatsSet(session: Pick<AdminSession, 'hasCapability'>): HomeStatsSet | undefined {
  if (session.hasCapability('MANAGE_OWN_PRODUCTS')) {
    return 'store';
  }
  if (session.hasCapability('MODERATE_PRODUCTS')) {
    return 'moderation';
  }
  return session.hasCapability('MANAGE_TAXONOMY') ? 'categories' : undefined;
}

export interface HomeStats {
  /** A última resposta do conjunto, que continua na tela enquanto a série de outro período carrega. */
  readonly value: Signal<HomeStatsValue | undefined>;
  readonly loading: Signal<boolean>;
  readonly problem: Signal<Problem | undefined>;
  reload(): void;
}

interface StatsSnapshot {
  readonly set: HomeStatsSet | undefined;
  readonly value: HomeStatsValue | undefined;
}

/**
 * Carrega os números do conjunto em `/stats/store`, `/stats/moderation` ou `/stats/categories`, com a série de
 * atividade na granularidade escolhida. Chamado num contexto de injeção.
 */
export function homeStats(set: () => HomeStatsSet | undefined, granularity: () => ActivityGranularity): HomeStats {
  const query = () => ({ query: { granularity: granularity() } });
  const store = getStoreStatsResource(() => (set() === 'store' ? query() : undefined));
  const moderation = getModerationStatsResource(() => (set() === 'moderation' ? query() : undefined));
  const categories = getCategoryStatsResource(() => (set() === 'categories' ? {} : undefined));

  const active = computed(() => {
    switch (set()) {
      case 'store':
        return store;
      case 'moderation':
        return moderation;
      case 'categories':
        return categories;
      default:
        return undefined;
    }
  });
  const current = computed<HomeStatsValue | undefined>(() => {
    switch (set()) {
      case 'store':
        return store.hasValue() ? { set: 'store', stats: store.value() } : undefined;
      case 'moderation':
        return moderation.hasValue() ? { set: 'moderation', stats: moderation.value() } : undefined;
      case 'categories':
        return categories.hasValue() ? { set: 'categories', stats: categories.value() } : undefined;
      default:
        return undefined;
    }
  });
  const value = linkedSignal<StatsSnapshot, HomeStatsValue | undefined>({
    source: () => ({ set: set(), value: current() }),
    computation: (next, previous) => {
      if (next.value) {
        return next.value;
      }
      const kept = previous?.value;
      return kept?.set === next.set ? kept : undefined;
    }
  });

  return {
    value,
    loading: computed(() => active()?.isLoading() ?? false),
    problem: computed(() => {
      const error = active()?.error();
      return error ? toProblem(error) : undefined;
    }),
    reload: () => {
      active()?.reload();
    }
  };
}
