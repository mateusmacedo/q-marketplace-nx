import { Component, computed, DestroyRef, DOCUMENT, effect, inject, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { NgScrollbar } from 'ngx-scrollbar';
import { NgScrollHoverVisibility } from 'ngx-scrollbar/addons';

import { IconDirective } from '@coreui/icons-angular';
import {
  ContainerComponent,
  ShadowOnScrollDirective,
  SidebarBrandComponent,
  SidebarComponent,
  SidebarFooterComponent,
  SidebarHeaderComponent,
  SidebarNavComponent,
  SidebarToggleDirective,
  SidebarTogglerDirective
} from '@coreui/angular';
import { getModerationQueueStats } from '@marketplace/catalog-inventory-admin-data-access';
import {
  allowsAccess,
  ErrorStateComponent,
  LeaveConfirmHostComponent,
  LoadingStateComponent,
  NavBadgeService,
  ToastHostComponent
} from '@marketplace/shared-admin-ui';
import { apiData } from '@marketplace/shared-web-util';

import { AuthSession } from '../../core/auth/auth-session';
import { SessionStore } from '../../core/auth/session-store';
import { DefaultFooterComponent, DefaultHeaderComponent } from './';
import { buildNav, navItems, QUEUE_BADGE } from './_nav';

@Component({
  selector: 'app-dashboard',
  templateUrl: './default-layout.component.html',
  styleUrls: ['./default-layout.component.scss'],
  imports: [
    SidebarComponent,
    SidebarHeaderComponent,
    SidebarBrandComponent,
    SidebarNavComponent,
    SidebarFooterComponent,
    SidebarToggleDirective,
    SidebarTogglerDirective,
    ContainerComponent,
    DefaultFooterComponent,
    DefaultHeaderComponent,
    ErrorStateComponent,
    IconDirective,
    LoadingStateComponent,
    NgScrollbar,
    NgScrollHoverVisibility,
    RouterOutlet,
    RouterLink,
    ShadowOnScrollDirective,
    LeaveConfirmHostComponent,
    ToastHostComponent
  ]
})
export class DefaultLayoutComponent {
  readonly #auth = inject(AuthSession);
  readonly #session = inject(SessionStore);
  readonly #badges = inject(NavBadgeService);
  readonly #router = inject(Router);

  readonly sessionStatus = this.#session.status;
  readonly sessionProblem = this.#session.problem;
  /** Menu do que a sessão libera, com os badges que as features alimentam. */
  readonly navItems = computed(() => buildNav(navItems, this.#session.grants(), this.#badges.counts()));
  /** Item da Fila, quando a sessão carregada o libera. */
  readonly #queueItem = computed(() =>
    this.#session.status() === 'ready'
      ? navItems.find(item => item.badgeKey === QUEUE_BADGE && allowsAccess(this.#session.grants(), item))
      : undefined
  );
  /** Caminho da rota, sem a query string: trocar só filtros ou cursor não muda o valor. */
  readonly #path = toSignal(
    this.#router.events.pipe(
      filter(event => event instanceof NavigationEnd),
      map(event => pathOf(event.urlAfterRedirects))
    ),
    { initialValue: pathOf(this.#router.url) }
  );

  constructor() {
    effect(() => {
      const queueUrl = this.#queueItem()?.url;
      const path = this.#path();
      // NOTE: dentro da fila, a própria tela atualiza o badge depois de cada decisão.
      if (typeof queueUrl === 'string' && !path.startsWith(queueUrl)) {
        untracked(() => void this.#refreshQueueBadge());
      }
    });
    const document = inject(DOCUMENT);
    const onVisible = () => {
      if (!document.hidden && typeof this.#queueItem()?.url === 'string') {
        void this.#refreshQueueBadge();
      }
    };
    document.addEventListener('visibilitychange', onVisible);
    inject(DestroyRef).onDestroy(() => document.removeEventListener('visibilitychange', onVisible));
  }

  /** Carrega a sessão de novo e refaz a navegação, para as guardas conferirem a rota com as capacidades carregadas. */
  async retrySession(): Promise<void> {
    await this.#session.load();
    if (this.#session.status() === 'ready') {
      await this.#router.navigateByUrl(this.#router.url, { onSameUrlNavigation: 'reload' });
    }
  }

  /** Leva ao provedor de login quando o servidor recusou o token, voltando para a rota atual. */
  signIn(): void {
    this.#auth.login(this.#router.url);
  }

  /**
   * Atualiza o badge da Fila com o tamanho da fila de moderação, de `GET /stats/moderation/queue`, reaproveitando a
   * leitura dos últimos 60 s.
   */
  #refreshQueueBadge(): Promise<void> {
    return this.#badges.refresh(QUEUE_BADGE, async () => (await apiData(getModerationQueueStats())).count);
  }
}

function pathOf(url: string): string {
  return url.split(/[?#]/, 1)[0];
}
