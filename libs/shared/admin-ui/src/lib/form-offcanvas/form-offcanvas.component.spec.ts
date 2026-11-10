import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import type { Problem } from '@marketplace/shared-web-util';

import { FormOffcanvasComponent } from './form-offcanvas.component';

@Component({
  imports: [FormOffcanvasComponent],
  template: `
    <app-form-offcanvas
      (cancelled)="cancelled = cancelled + 1"
      (reload)="reloads = reloads + 1"
      (save)="saves = saves + 1"
      [(visible)]="visible"
      [canSave]="canSave()"
      [problem]="problem()"
      [saving]="saving()"
      problemHeading="Não foi possível salvar"
      subtitle="wrong-category"
      title="Editar motivo"
    >
      <label for="label">Rótulo</label>
      <input id="label" type="text" value="Categoria incorreta" />
    </app-form-offcanvas>
  `,
})
class OffcanvasHost {
  readonly visible = signal(true);
  readonly saving = signal(false);
  readonly canSave = signal(true);
  readonly problem = signal<Problem | null>(null);
  saves = 0;
  cancelled = 0;
  reloads = 0;
}

describe('FormOffcanvasComponent', () => {
  let fixture: ComponentFixture<OffcanvasHost>;

  const element = () => fixture.nativeElement as HTMLElement;
  const button = (text: string) =>
    (Array.from(element().querySelectorAll('c-offcanvas-body button')) as HTMLButtonElement[]).find(
      (item) => item.textContent?.trim() === text,
    ) as HTMLButtonElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [OffcanvasHost] }).compileComponents();
    fixture = TestBed.createComponent(OffcanvasHost);
    await fixture.whenStable();
  });

  it('mostra o título, a linha de apoio e os campos projetados dentro do formulário', () => {
    expect(element().querySelector('[cOffcanvasTitle]')?.textContent?.trim()).toBe('Editar motivo');
    expect(element().querySelector('c-offcanvas-header small')?.textContent).toContain(
      'wrong-category',
    );
    expect(element().querySelector('form #label')).toBeTruthy();
    expect(button('Cancelar').classList).toContain('btn-secondary');
  });

  it('põe cabeçalho e corpo no flex do painel, para o corpo rolar e o botão de salvar ficar alcançável', () => {
    const offcanvas = element().querySelector('.offcanvas') as HTMLElement;
    const wrapper = offcanvas.querySelector(
      ':scope > div:not(.cdk-focus-trap-anchor)',
    ) as HTMLElement;

    expect(wrapper.contains(offcanvas.querySelector('c-offcanvas-body'))).toBe(true);
    expect(getComputedStyle(wrapper).display).toBe('contents');
  });

  it('emite o envio pelo botão de salvar e pelo Enter no formulário', () => {
    button('Salvar').click();
    element()
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', { cancelable: true }));
    expect(fixture.componentInstance.saves).toBe(2);
  });

  it('trava os botões e não envia durante a gravação ou com o formulário inválido', async () => {
    fixture.componentInstance.saving.set(true);
    await fixture.whenStable();
    expect(button('Salvar').disabled).toBe(true);
    expect(button('Salvar').querySelector('c-spinner')).toBeTruthy();
    expect(button('Cancelar').disabled).toBe(true);

    fixture.componentInstance.saving.set(false);
    fixture.componentInstance.canSave.set(false);
    await fixture.whenStable();
    element()
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', { cancelable: true }));
    expect(fixture.componentInstance.saves).toBe(0);
  });

  it('fecha em Cancelar', async () => {
    button('Cancelar').click();
    await fixture.whenStable();
    expect(fixture.componentInstance.visible()).toBe(false);
    expect(fixture.componentInstance.cancelled).toBe(1);
  });

  it('mostra a recusa com a mensagem do servidor', async () => {
    fixture.componentInstance.problem.set({
      type: 'https://marketplace.dev/problems/moderation-reason-limit',
      status: 422,
      reasonDetails: [
        { code: 'moderation-reason-limit', message: 'O tenant já tem 100 motivos ativos.' },
      ],
    });
    await fixture.whenStable();

    const alert = element().querySelector('app-problem-alert');
    expect(alert?.textContent).toContain('Não foi possível salvar');
    expect(alert?.textContent).toContain('O tenant já tem 100 motivos ativos.');
    expect(alert?.textContent).not.toContain('moderation-reason-limit');
  });

  it('abre o aviso de conflito no 412 e pede a versão atual', async () => {
    fixture.componentInstance.problem.set({
      type: 'https://marketplace.dev/problems/version-mismatch',
      status: 412,
    });
    await fixture.whenStable();

    expect(element().querySelector('app-problem-alert')).toBeNull();
    button('Carregar versão atual').click();
    expect(fixture.componentInstance.reloads).toBe(1);
  });
});
