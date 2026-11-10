import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { CardBodyComponent, CardComponent } from '@coreui/angular';
import { AdminSession, EmptyStateComponent } from '@marketplace/shared-admin-ui';

import {
  CatalogActivityCardComponent,
  type PeriodFailure,
} from './catalog-activity-card.component';
import type { ActivityGranularity } from './home-activity';
import {
  categoryIndicators,
  type HomeIndicator,
  moderationIndicators,
  storeIndicators,
} from './home-indicators';
import { type HomeLinks, productsLink } from './home-links';
import { homeStats, homeStatsSet } from './home-stats';
import { HomeWidgetsComponent } from './home-widgets.component';
import { RecentProductsCardComponent } from './recent-products-card.component';

/**
 * Conteúdo da página inicial (proposta de design, seção "1. Início"), no formato do dashboard do template: os 4
 * indicadores do conjunto que a capacidade da sessão escolhe, o card "Catálogo" com a série de atividade e a tabela dos
 * produtos atualizados por último. O app carrega este componente sob demanda na rota `/inicio` e passa os endereços
 * das telas que a sessão abre.
 */
@Component({
  selector: 'app-home-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CardBodyComponent,
    CardComponent,
    CatalogActivityCardComponent,
    EmptyStateComponent,
    HomeWidgetsComponent,
    RecentProductsCardComponent,
  ],
  template: `
    @if (statsSet(); as set) {
      <app-home-widgets
        (retry)="stats.reload()"
        [indicators]="indicators()"
        [loading]="stats.loading()"
        [problem]="stats.problem()"
      />
      @if (set !== 'categories' && stats.value()) {
        <app-catalog-activity-card
          (granularityChange)="choosePeriod($event)"
          [activity]="activity()"
          [counts]="counts()"
          [failure]="periodFailure()"
          [granularity]="granularity()"
          [loading]="stats.loading()"
        />
      }
    }
    @if (showProducts()) {
      <app-recent-products-card [links]="links()" />
    }
    @if (!statsSet() && !showProducts()) {
      <c-card>
        <c-card-body>
          <app-empty-state
            message="Sua conta ainda não tem acesso ao catálogo. Peça acesso ao administrador da plataforma."
          />
        </c-card-body>
      </c-card>
    }
  `,
})
export class HomeDashboardComponent {
  readonly #session = inject(AdminSession);

  readonly links = input<HomeLinks>({});

  readonly granularity = signal<ActivityGranularity>('month');
  readonly statsSet = computed(() => homeStatsSet(this.#session));
  readonly stats = homeStats(this.statsSet, this.granularity);
  readonly periodFailure = signal<PeriodFailure | null>(null);

  readonly indicators = computed<HomeIndicator[]>(() => {
    const value = this.stats.value();
    switch (value?.set) {
      case 'store':
        return storeIndicators(value.stats, this.links());
      case 'moderation':
        return moderationIndicators(value.stats, this.links());
      case 'categories':
        return categoryIndicators(value.stats, this.links());
      default:
        return [];
    }
  });

  readonly activity = computed(() => {
    const value = this.stats.value();
    return value && value.set !== 'categories' ? value.stats.activity : undefined;
  });

  readonly counts = computed(() => {
    const value = this.stats.value();
    return value?.set === 'store' ? value.stats.counts : undefined;
  });

  readonly showProducts = computed(() => productsLink(this.links()) !== undefined);

  constructor() {
    effect(() => {
      const problem = this.stats.problem();
      const shown = this.activity()?.granularity;
      const chosen = this.granularity();
      if (problem && shown && shown !== chosen) {
        // WHY: a série anterior continua no gráfico; o seletor volta a ela para não mostrar um período que não carregou.
        untracked(() => {
          this.periodFailure.set({ granularity: chosen, problem });
          this.granularity.set(shown);
        });
      }
    });
  }

  choosePeriod(granularity: ActivityGranularity): void {
    this.periodFailure.set(null);
    this.granularity.set(granularity);
  }
}
