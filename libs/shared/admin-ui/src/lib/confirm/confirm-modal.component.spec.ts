import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ConfirmModalComponent } from './confirm-modal.component';

@Component({
  imports: [ConfirmModalComponent],
  template: `
    <app-confirm-modal
      (confirm)="confirmed = confirmed + 1"
      [(visible)]="visible"
      [busy]="busy()"
      [returnFocus]="opener()"
      confirmColor="danger"
      confirmLabel="Arquivar"
      title="Arquivar produto"
    >
      <p>"Camiseta Basic" sai da vitrine na hora.</p>
    </app-confirm-modal>
  `,
})
class ModalHost {
  readonly visible = signal(true);
  readonly busy = signal(false);
  readonly opener = signal<HTMLElement | null>(null);
  confirmed = 0;
}

describe('ConfirmModalComponent', () => {
  let fixture: ComponentFixture<ModalHost>;

  const button = (text: string) =>
    (
      Array.from(
        fixture.nativeElement.querySelectorAll('c-modal-footer button'),
      ) as HTMLButtonElement[]
    ).find((item) => item.textContent?.trim() === text) as HTMLButtonElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [ModalHost] }).compileComponents();
    fixture = TestBed.createComponent(ModalHost);
    await fixture.whenStable();
  });

  it('mostra o título, o corpo projetado e Cancelar em btn-secondary', () => {
    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('[cModalTitle]')?.textContent?.trim()).toBe('Arquivar produto');
    expect(element.querySelector('c-modal-body')?.textContent).toContain('sai da vitrine na hora');
    expect(button('Cancelar').classList).toContain('btn-secondary');
    expect(button('Arquivar').classList).toContain('btn-danger');
  });

  it('emite a confirmação sem fechar, para a tela gravar antes', () => {
    button('Arquivar').click();
    expect(fixture.componentInstance.confirmed).toBe(1);
    expect(fixture.componentInstance.visible()).toBe(true);
  });

  it('fecha em Cancelar', async () => {
    button('Cancelar').click();
    await fixture.whenStable();
    expect(fixture.componentInstance.visible()).toBe(false);
  });

  it('devolve o foco ao elemento indicado ao fechar', async () => {
    const opener = document.createElement('button');
    document.body.appendChild(opener);
    fixture.componentInstance.opener.set(opener);
    await fixture.whenStable();

    button('Cancelar').click();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 10));

    expect(document.activeElement).toBe(opener);
    opener.remove();
  });

  it('trava os botões durante a gravação', async () => {
    fixture.componentInstance.busy.set(true);
    await fixture.whenStable();
    expect(button('Cancelar').disabled).toBe(true);
    expect(button('Arquivar').disabled).toBe(true);
    expect(button('Arquivar').querySelector('c-spinner')).toBeTruthy();
  });
});
