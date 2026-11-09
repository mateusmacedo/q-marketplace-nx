import type {
  CategoryStatsResponse,
  IndicatorResponse,
  ModerationStatsResponse,
  MonthValueResponse,
  StatusCountsResponse,
  StoreStatsResponse
} from '@marketplace/catalog-inventory-admin-data-access';

import type { HomeLinks } from './home-links';

/** Cor de cada indicador, pela posição, como os 4 do dashboard do template. */
export type IndicatorColor = 'primary' | 'info' | 'warning' | 'danger';

const COLORS: readonly IndicatorColor[] = ['primary', 'info', 'warning', 'danger'];

/** Atalho do dropdown do indicador, que leva à lista filtrada. */
export interface IndicatorLink {
  readonly label: string;
  readonly url: string;
  readonly query?: Readonly<Record<string, string>>;
}

/** Um card `c-widget-stat-a` da página inicial. */
export interface HomeIndicator {
  readonly key: string;
  readonly title: string;
  readonly color: IndicatorColor;
  readonly value: number;
  /** Variação em relação ao fim do mês anterior; ausente quando o servidor não manda `previous` e `change`. */
  readonly change?: number;
  /** Meses do minigráfico, do mais antigo ao corrente; ausente quando o servidor não manda a série. */
  readonly months?: readonly MonthValueResponse[];
  readonly links: readonly IndicatorLink[];
}

type IndicatorDraft = Omit<HomeIndicator, 'color'>;

// WHY: cópia pendente (ADR 031); saída: lista indicators em /stats/store, /stats/moderation e /stats/categories (code,
// label, value, previous, change e months), com o painel mapeando code para ícone e cor.
/** Produtos ativos, em rascunho, sem estoque e bloqueados da loja (`GET /stats/store`). */
export function storeIndicators(stats: StoreStatsResponse, links: HomeLinks): HomeIndicator[] {
  const list = (label: string, status: string) => link(label, links.storeProducts, { status });
  return inOrder([
    fromServer('active', 'Produtos ativos', stats.active, [
      list('Ver os ativos', 'ACTIVE'),
      list('Ver os pausados', 'PAUSED_BY_SELLER')
    ]),
    fromServer('draft', 'Em rascunho', stats.draft, [list('Ver os rascunhos', 'DRAFT')]),
    fromServer('outOfStock', 'Sem estoque', stats.outOfStock, [list('Ver os sem estoque', 'OUT_OF_STOCK')]),
    fromServer('blocked', 'Bloqueados', stats.blocked, [list('Ver os bloqueados', 'BLOCKED')])
  ]);
}

// WHY: cópia pendente (ADR 031); saída: lista indicators em /stats/store, /stats/moderation e /stats/categories (code,
// label, value, previous, change e months), com o painel mapeando code para ícone e cor.
/** Fila e decisões do mês da moderação (`GET /stats/moderation`). */
export function moderationIndicators(stats: ModerationStatsResponse, links: HomeLinks): HomeIndicator[] {
  const queue = link('Abrir a fila', links.moderationQueue);
  return inOrder([
    fromServer('queue', 'Na fila de moderação', stats.queue, [queue]),
    fromServer('approved', 'Aprovados no mês', stats.approved, [link('Ver os produtos', links.tenantProducts)]),
    fromServer('rejected', 'Rejeitados no mês', stats.rejected, [queue]),
    fromServer('blocked', 'Bloqueados no mês', stats.blocked, [
      link('Ver os bloqueados', links.tenantProducts, { status: 'BLOCKED' })
    ])
  ]);
}

// WHY: cópia pendente (ADR 031); saída: lista indicators em /stats/store, /stats/moderation e /stats/categories (code,
// label, value, previous, change e months), com o painel mapeando code para ícone e cor.
/** Números da árvore de categorias (`GET /stats/categories`), que não trazem variação nem série. */
export function categoryIndicators(stats: CategoryStatsResponse, links: HomeLinks): HomeIndicator[] {
  const tree = link('Abrir as categorias', links.categories);
  return inOrder([
    { key: 'total', title: 'Categorias', value: stats.total ?? 0, links: compact([tree]) },
    { key: 'roots', title: 'Categorias raiz', value: stats.roots ?? 0, links: compact([tree]) },
    { key: 'inactive', title: 'Inativas', value: stats.inactive ?? 0, links: compact([tree]) },
    {
      key: 'withProducts',
      title: 'Com produtos',
      value: stats.withProducts ?? 0,
      links: compact([link('Ver os produtos', links.tenantProducts)])
    }
  ]);
}

/** Um dos números do rodapé do card "Catálogo", com a parte que ele representa na loja. */
export interface StatusShare {
  readonly label: string;
  readonly count: number;
  /** Porcentagem dos produtos da loja, sem os arquivados (`total`), arredondada. */
  readonly percent: number;
  readonly color: string;
}

/** Os 5 números do rodapé: ativos, em rascunho, em moderação, bloqueados e pausados, com a parte de cada um. */
export function statusShares(counts: StatusCountsResponse): StatusShare[] {
  const total = counts.total ?? 0;
  const share = (label: string, count: number | undefined, color: string): StatusShare => ({
    label,
    count: count ?? 0,
    percent: total > 0 ? Math.round(((count ?? 0) * 100) / total) : 0,
    color
  });
  return [
    share('Ativos', counts.active, 'success'),
    share('Em rascunho', counts.draft, 'info'),
    share('Em moderação', counts.pendingModeration, 'warning'),
    share('Bloqueados', counts.blocked, 'danger'),
    share('Pausados', counts.pausedBySeller, 'primary')
  ];
}

function fromServer(
  key: string,
  title: string,
  indicator: IndicatorResponse | undefined,
  links: readonly (IndicatorLink | undefined)[]
): IndicatorDraft {
  const hasChange =
    indicator?.previous !== null &&
    indicator?.previous !== undefined &&
    indicator.change !== null &&
    indicator.change !== undefined;
  return {
    key,
    title,
    value: indicator?.value ?? 0,
    change: hasChange ? (indicator.change ?? undefined) : undefined,
    months: indicator?.months?.length ? indicator.months : undefined,
    links: compact(links)
  };
}

function link(label: string, url: string | undefined, query?: Record<string, string>): IndicatorLink | undefined {
  return url ? { label, url, ...(query ? { query } : {}) } : undefined;
}

function compact(links: readonly (IndicatorLink | undefined)[]): IndicatorLink[] {
  return links.filter((item): item is IndicatorLink => item !== undefined);
}

function inOrder(drafts: readonly IndicatorDraft[]): HomeIndicator[] {
  return drafts.map((draft, index) => ({ ...draft, color: COLORS[index % COLORS.length] }));
}
