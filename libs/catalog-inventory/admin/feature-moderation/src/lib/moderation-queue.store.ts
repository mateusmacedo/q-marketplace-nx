import { computed, DestroyRef, effect, inject, Injectable, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router } from '@angular/router';
import {
  getModerationQueueStats,
  moderationQueue,
  type ProductResponse
} from '@marketplace/catalog-inventory-admin-data-access';
import { ListSelection, NavBadgeService, type NavBadgeRefreshOptions } from '@marketplace/shared-admin-ui';
import { apiData, type Problem, toPageSize, toProblem } from '@marketplace/shared-web-util';
import { filter, map } from 'rxjs';

import { MODERATION_QUEUE_BADGE } from './queue-badge';

interface QueueQuery {
  readonly limit: number;
  readonly cursor?: string;
}

/**
 * Estado da fila de moderação, compartilhado pela lista e pelo detalhe aberto ao lado: a página de
 * `GET /moderation/products` (na ordem de chegada, que o servidor define), a seleção do lote, o item aberto e o badge
 * da Fila no menu. A tela da fila fornece a instância, e o detalhe, carregado no `router-outlet` dela, a injeta.
 */
@Injectable()
export class ModerationQueueStore {
  readonly #route = inject(ActivatedRoute);
  readonly #router = inject(Router);
  readonly #badges = inject(NavBadgeService);
  readonly #query = toSignal(this.#route.queryParamMap, { requireSync: true });

  readonly limit = computed(() => toPageSize(this.#query().get('limit')));
  readonly cursor = computed(() => this.#query().get('cursor') || undefined);
  readonly items = signal<readonly ProductResponse[]>([]);
  readonly nextCursor = signal<string | undefined>(undefined);
  readonly loading = signal(false);
  /** A primeira página já respondeu, com dados ou erro. */
  readonly loaded = signal(false);
  readonly problem = signal<Problem | null>(null);
  readonly selection = new ListSelection<ProductResponse>({ id: product => product.id ?? '' });
  /** Produto aberto no detalhe (`/moderacao/fila/:id`). */
  readonly openId = toSignal(
    this.#router.events.pipe(
      filter(event => event instanceof NavigationEnd),
      map(() => this.#childId())
    ),
    { initialValue: this.#childId() }
  );

  #request = 0;
  #destroyed = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => (this.#destroyed = true));
    effect(() => {
      const query: QueueQuery = { limit: this.limit(), cursor: this.cursor() };
      untracked(() => void this.#load(query));
    });
    void this.refreshBadge();
  }

  reload(): Promise<void> {
    return this.#load({ limit: this.limit(), cursor: this.cursor() });
  }

  /**
   * Atualiza o badge da Fila no menu com o tamanho da fila, de `GET /stats/moderation/queue`. Sem `force`, reaproveita
   * a leitura dos últimos 60 s, a mesma que o menu usa; depois de uma decisão, `force` lê de novo.
   */
  refreshBadge(options: NavBadgeRefreshOptions = {}): Promise<void> {
    return this.#badges.refresh(
      MODERATION_QUEUE_BADGE,
      async () => (await apiData(getModerationQueueStats())).count,
      options
    );
  }

  /**
   * Recarrega a fila e o badge depois de uma decisão e tira os decididos da seleção do lote, que guardaria as ações e a
   * versão antigas. Se o produto aberto foi decidido, abre o que vinha depois dele
   * na fila, ou o primeiro que restou; sem nenhum, volta para a lista. Depois que a pessoa saiu da fila, não navega.
   */
  async afterDecision(decided: readonly string[]): Promise<void> {
    this.selection.remove(decided);
    const openId = this.openId();
    const before = this.items();
    const position = openId ? before.findIndex(product => product.id === openId) : -1;
    const following = position < 0 ? [] : before.slice(position + 1).map(product => product.id);
    await this.reload();
    void this.refreshBadge({ force: true });
    if (this.#destroyed || !openId || !decided.includes(openId)) {
      return;
    }
    const remaining = this.items().filter(product => product.id && !decided.includes(product.id));
    const next = remaining.find(product => following.includes(product.id)) ?? remaining[0];
    await this.#router.navigate(next?.id ? [next.id] : ['.'], { relativeTo: this.#route, queryParamsHandling: 'preserve' });
  }

  /** Abre outra página ou outro tamanho de página, sem fechar o detalhe aberto. */
  changePage(query: { readonly cursor?: string | null; readonly limit?: number }): void {
    void this.#router.navigate([], { queryParams: query, queryParamsHandling: 'merge' });
  }

  async #load(query: QueueQuery): Promise<void> {
    const request = ++this.#request;
    this.loading.set(true);
    try {
      const page = await apiData(moderationQueue({ query: { limit: query.limit, cursor: query.cursor } }));
      if (request !== this.#request) {
        return;
      }
      const items = page.items ?? [];
      this.items.set(items);
      this.nextCursor.set(page.nextCursor ?? undefined);
      this.problem.set(null);
      this.selection.setPage(items, { limit: query.limit });
    } catch (error) {
      if (request === this.#request) {
        this.problem.set(toProblem(error));
      }
    } finally {
      if (request === this.#request) {
        this.loading.set(false);
        this.loaded.set(true);
      }
    }
  }

  #childId(): string | undefined {
    return this.#route.firstChild?.snapshot?.paramMap.get('id') ?? undefined;
  }
}
