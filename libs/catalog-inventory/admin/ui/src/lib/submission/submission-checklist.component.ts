import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import {
  CardBodyComponent,
  CardComponent,
  CardFooterComponent,
  CardHeaderComponent,
  ListGroupDirective,
  ListGroupItemDirective,
  SpinnerComponent
} from '@coreui/angular';
import { cilCheckCircle, cilXCircle } from '@coreui/icons';
import { IconDirective } from '@coreui/icons-angular';

import { type ChecklistItem, checklistItems, type PublishingIssue } from './publishing-issues';

/**
 * Checklist de envio à moderação, ao lado das abas do produto. Mostra as pendências que o servidor calcula: as do
 * produto salvo (`publishingIssues`) ou as do rascunho conferido por `POST /products/{id}/check`, que a feature chama
 * com espera entre as digitações. Cada pendência leva à aba e ao campo onde se resolve (`go`).
 */
@Component({
  selector: 'app-submission-checklist',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CardBodyComponent,
    CardComponent,
    CardFooterComponent,
    CardHeaderComponent,
    IconDirective,
    ListGroupDirective,
    ListGroupItemDirective,
    SpinnerComponent
  ],
  template: `
    <c-card class="mb-4">
      <c-card-header class="d-flex align-items-center">
        <span class="me-auto">Para enviar à moderação</span>
        @if (loading()) {
          <c-spinner label="Conferindo as pendências" size="sm" />
        }
      </c-card-header>
      @if (items().length) {
        <div [attr.aria-busy]="loading()" cListGroup flush>
          @for (item of items(); track $index) {
            <button (click)="go.emit(item)" cListGroupItem class="list-group-item-action d-flex align-items-start" type="button">
              <svg [cIcon]="pendingIcon" aria-hidden="true" class="me-2 mt-1 text-danger flex-shrink-0"></svg>
              <span>{{ item.message }}</span>
            </button>
          }
        </div>
      } @else if (!loading()) {
        <c-card-body>
          <svg [cIcon]="readyIcon" aria-hidden="true" class="me-2 text-success"></svg>
          Tudo pronto para enviar.
        </c-card-body>
      }
      <c-card-footer class="small text-body-secondary">{{ footer() }}</c-card-footer>
    </c-card>
  `
})
export class SubmissionChecklistComponent {
  /** Pendências calculadas pelo servidor, na ordem da política de publicação. */
  readonly issues = input<readonly PublishingIssue[] | null | undefined>();
  /** Conferência do rascunho em andamento. */
  readonly loading = input(false);
  /** As pendências vieram da recusa do envio (422), e não da conferência. */
  readonly refused = input(false);

  /** Pendência escolhida: a tela abre a aba e põe o foco no campo. */
  readonly go = output<ChecklistItem>();

  readonly pendingIcon = cilXCircle;
  readonly readyIcon = cilCheckCircle;

  readonly items = computed(() => checklistItems(this.issues()));
  readonly footer = computed(() => {
    if (this.loading()) {
      return 'Conferindo o rascunho com as regras de publicação.';
    }
    return this.refused()
      ? 'Motivos da recusa do envio à moderação.'
      : 'Conferido pelo servidor com as regras de publicação.';
  });
}
