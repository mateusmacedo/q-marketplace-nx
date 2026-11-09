import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideCatalogInventoryApi, type ModerationReasonResponse } from '@marketplace/catalog-inventory-admin-data-access';
import type { Problem } from '@marketplace/shared-web-util';

import { type DecisionReason, ModerationDecisionModalComponent } from './moderation-decision-modal.component';
import type { ReasonDecision } from './moderation-reasons';

const REJECT_REASONS: ModerationReasonResponse[] = [
  { code: 'categoria-incorreta', label: 'Categoria incorreta', decisions: ['REJECT'], position: 10, active: true },
  { code: 'descricao-enganosa', label: 'Descrição enganosa', decisions: ['REJECT', 'BLOCK'], position: 20, active: true }
];

@Component({
  imports: [ModerationDecisionModalComponent],
  template: `
    <app-moderation-decision-modal
      (closed)="closed = closed + 1"
      (confirm)="confirmed.push($event)"
      (reload)="reloads = reloads + 1"
      [batch]="batch()"
      [busy]="busy()"
      [decision]="decision()"
      [problem]="problem()"
      [subject]="subject()"
    />
  `
})
class ModalHost {
  readonly decision = signal<ReasonDecision | null>(null);
  readonly subject = signal('Camiseta Basic');
  readonly batch = signal(false);
  readonly busy = signal(false);
  readonly problem = signal<Problem | null>(null);
  readonly confirmed: DecisionReason[] = [];
  closed = 0;
  reloads = 0;
}

describe('ModerationDecisionModalComponent', () => {
  let fixture: ComponentFixture<ModalHost>;
  let http: HttpTestingController;

  const host = () => fixture.componentInstance;
  const element = () => fixture.nativeElement as HTMLElement;
  const footerButton = (text: string) =>
    (Array.from(element().querySelectorAll('c-modal-footer button')) as HTMLButtonElement[]).find(
      button => button.textContent?.trim() === text
    ) as HTMLButtonElement;
  const checkboxes = () => Array.from(element().querySelectorAll('c-modal-body input[type=checkbox]')) as HTMLInputElement[];
  const note = () => element().querySelector('textarea') as HTMLTextAreaElement;

  async function open(decision: ReasonDecision, reasons: ModerationReasonResponse[]): Promise<void> {
    host().decision.set(decision);
    TestBed.tick();
    http.expectOne(`/api/catalog-inventory/moderation/reasons?decision=${decision}`).flush(reasons);
    await fixture.whenStable();
  }

  async function type(text: string): Promise<void> {
    note().value = text;
    note().dispatchEvent(new Event('input'));
    note().dispatchEvent(new Event('blur'));
    await fixture.whenStable();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ModalHost],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideCatalogInventoryApi()]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ModalHost);
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  it('não lê os motivos com o modal fechado', () => {
    http.expectNone(() => true);
  });

  it('abre com os motivos da decisão, o contador do complemento pelo contrato e o efeito', async () => {
    await open('REJECT', REJECT_REASONS);

    expect(element().querySelector('[cModalTitle]')?.textContent?.trim()).toBe('Rejeitar "Camiseta Basic"');
    expect(Array.from(element().querySelectorAll('c-modal-body label[cFormCheckLabel]')).map(label => label.textContent?.trim())).toEqual([
      'Categoria incorreta',
      'Descrição enganosa'
    ]);
    expect(element().textContent).toContain('0 de 500');
    expect(note().getAttribute('maxlength')).toBe('500');
    expect(element().textContent).toContain('O produto volta ao rascunho do lojista.');
    expect(footerButton('Rejeitar').classList).toContain('btn-warning');
    expect(element().textContent).not.toContain('categoria-incorreta');
  });

  it('sem motivo marcado, exige o complemento antes de confirmar', async () => {
    await open('REJECT', REJECT_REASONS);

    footerButton('Rejeitar').click();
    await fixture.whenStable();
    expect(host().confirmed).toEqual([]);
    expect(element().textContent).toContain('Escreva o complemento ou marque um motivo.');

    await type('A gola não bate com a foto.');
    expect(element().textContent).toContain('27 de 500');
    footerButton('Rejeitar').click();

    expect(host().confirmed).toEqual([{ reasonCodes: [], note: 'A gola não bate com a foto.' }]);
  });

  it('envia os motivos marcados, com o complemento opcional', async () => {
    await open('REJECT', REJECT_REASONS);

    checkboxes()[1].click();
    await fixture.whenStable();
    expect(element().textContent).not.toContain('obrigatório sem motivo marcado');
    footerButton('Rejeitar').click();

    expect(host().confirmed).toEqual([{ reasonCodes: ['descricao-enganosa'], note: null }]);
  });

  it('com a lista vazia, avisa que o administrador cadastra os motivos e decide só com o complemento', async () => {
    await open('BLOCK', []);

    expect(checkboxes()).toEqual([]);
    expect(element().textContent).toContain('Plataforma › Motivos de moderação');
    expect(element().textContent).toContain('O produto sai da vitrine na hora.');
    await type('Item proibido.');
    footerButton('Bloquear').click();

    expect(host().confirmed).toEqual([{ reasonCodes: [], note: 'Item proibido.' }]);
  });

  it('na recusa de um motivo que mudou, mostra a mensagem do servidor, recarrega a lista e mantém o complemento', async () => {
    await open('REJECT', REJECT_REASONS);
    checkboxes()[0].click();
    await type('Complemento digitado.');

    host().problem.set({
      type: 'https://marketplace.dev/problems/invalid-moderation-reason',
      status: 422,
      title: 'Motivo inválido',
      reasons: ['reason-inactive:categoria-incorreta'],
      reasonDetails: [{ code: 'reason-inactive', message: 'O motivo Categoria incorreta foi desativado.' }]
    });
    TestBed.tick();
    http.expectOne('/api/catalog-inventory/moderation/reasons?decision=REJECT').flush(REJECT_REASONS.slice(1));
    await fixture.whenStable();

    expect(element().textContent).toContain('O motivo Categoria incorreta foi desativado.');
    expect(element().textContent).not.toContain('reason-inactive');
    expect(checkboxes().length).toBe(1);
    expect(note().value).toBe('Complemento digitado.');

    host().problem.set(null);
    await fixture.whenStable();
    footerButton('Rejeitar').click();
    expect(host().confirmed).toEqual([{ reasonCodes: [], note: 'Complemento digitado.' }]);
  });

  it('no conflito de edição, mostra o aviso e pede a versão atual', async () => {
    await open('BLOCK', REJECT_REASONS);
    host().problem.set({ type: 'https://marketplace.dev/problems/version-mismatch', status: 412, title: 'Versão desatualizada' });
    await fixture.whenStable();

    const reload = (Array.from(element().querySelectorAll('button')) as HTMLButtonElement[]).find(button =>
      button.textContent?.includes('Carregar versão atual')
    ) as HTMLButtonElement;
    reload.click();

    expect(host().reloads).toBe(1);
  });

  it('no lote, o título leva a contagem e o efeito fala dos produtos', async () => {
    host().batch.set(true);
    host().subject.set('3 produtos');
    await open('BLOCK', REJECT_REASONS);

    expect(element().querySelector('[cModalTitle]')?.textContent?.trim()).toBe('Bloquear 3 produtos');
    expect(element().textContent).toContain('Os produtos saem da vitrine na hora.');
  });

  it('fecha pelo Cancelar e trava durante a gravação', async () => {
    await open('REJECT', REJECT_REASONS);
    footerButton('Cancelar').click();
    expect(host().closed).toBe(1);

    host().busy.set(true);
    await fixture.whenStable();
    expect(footerButton('Cancelar').disabled).toBe(true);
    expect(footerButton('Rejeitar').disabled).toBe(true);
  });
});
