import { ChangeDetectionStrategy, Component, computed, effect, inject, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import {
  CardBodyComponent,
  CardComponent,
  ColComponent,
  NavComponent,
  NavItemComponent,
  NavLinkDirective,
  RowComponent
} from '@coreui/angular';
import { type ChecklistItem, SubmissionChecklistComponent } from '@marketplace/catalog-inventory-admin-ui';
import { ErrorStateComponent, LoadingStateComponent } from '@marketplace/shared-admin-ui';
import { map } from 'rxjs';

import { productDraftCheck } from './product-draft-check';
import { ProductHeaderComponent } from './product-header.component';
import { ProductStore } from './product-store';
import { PRODUCT_TABS } from './product-tabs';

/**
 * Tela do produto da loja (`/loja/produtos/:id`): o cabeçalho com o status, a ação principal e o menu "..." com as
 * mesmas ações da lista, as abas em rotas filhas e, quando o servidor oferece o envio, o checklist ao lado. As ações,
 * as regras de edição e as pendências vêm do servidor (ADR 031); a tela guarda só a apresentação.
 */
@Component({
  selector: 'app-product-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ProductStore],
  imports: [
    CardBodyComponent,
    CardComponent,
    ColComponent,
    ErrorStateComponent,
    LoadingStateComponent,
    NavComponent,
    NavItemComponent,
    NavLinkDirective,
    ProductHeaderComponent,
    RouterLink,
    RouterLinkActive,
    RouterOutlet,
    RowComponent,
    SubmissionChecklistComponent
  ],
  template: `
    @if (store.product()) {
      <app-product-header />

      <c-row class="g-4">
        <c-col [lg]="showChecklist() ? 8 : 12" [xs]="12">
          <c-nav aria-label="Seções do produto" class="mb-3" variant="tabs">
            @for (tab of tabs; track tab.path) {
              <c-nav-item>
                <a
                  #link="routerLinkActive"
                  [active]="link.isActive"
                  [routerLink]="tab.path"
                  ariaCurrentWhenActive="page"
                  cNavLink
                  routerLinkActive
                >
                  {{ tab.label }}
                </a>
              </c-nav-item>
            }
          </c-nav>
          <router-outlet />
        </c-col>
        @if (showChecklist()) {
          <c-col [lg]="4" [xs]="12">
            <app-submission-checklist
              (go)="go($event)"
              [issues]="checklistIssues()"
              [loading]="draftCheck.loading()"
              [refused]="store.refusal() !== null"
            />
            @if (draftCheck.problem(); as problem) {
              <app-error-state
                (retry)="draftCheck.reload()"
                [problem]="problem"
                message="Não foi possível conferir o rascunho."
              />
            }
          </c-col>
        }
      </c-row>
    } @else if (store.problem(); as problem) {
      <c-card class="mb-4">
        <c-card-body>
          <h1 class="h4 card-title mb-0">Produto</h1>
          <app-error-state (retry)="retry()" [problem]="problem" message="Não foi possível abrir o produto." />
        </c-card-body>
      </c-card>
    } @else {
      <c-card class="mb-4">
        <c-card-body>
          <h1 class="h4 card-title mb-0">Produto</h1>
          <app-loading-state [rows]="3" label="Carregando o produto…" />
        </c-card-body>
      </c-card>
    }
  `
})
export class ProductPageComponent {
  readonly store = inject(ProductStore);
  readonly #route = inject(ActivatedRoute);
  readonly #router = inject(Router);

  readonly tabs = PRODUCT_TABS;
  readonly #id = toSignal(this.#route.paramMap.pipe(map(params => params.get('id'))), { initialValue: null });
  readonly showChecklist = computed(() => this.store.rules().showChecklist);

  /** Pendências do produto salvo ou, com mudanças não salvas no conteúdo, as do rascunho conferido no servidor. */
  readonly draftCheck = productDraftCheck(
    () => this.store.product(),
    () => this.store.draft()
  );
  readonly checklistIssues = computed(() => this.store.refusal() ?? this.draftCheck.issues());

  constructor() {
    effect(() => {
      const id = this.#id();
      if (id) {
        untracked(() => void this.store.load(id));
      }
    });
    effect(() => {
      this.store.draft();
      untracked(() => this.store.refusal.set(null));
    });
  }

  retry(): void {
    const id = this.#id();
    if (id) {
      void this.store.load(id);
    }
  }

  /** Abre a aba da pendência; a aba põe o foco no campo dela. */
  async go(item: ChecklistItem): Promise<void> {
    const opened = await this.#router.navigate([item.tab], { relativeTo: this.#route });
    if (opened !== false) {
      this.store.focus.set(item);
    }
  }
}
