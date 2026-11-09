import { Component, computed, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import type { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';

import { LeaveConfirmHostComponent } from './leave-confirm-host.component';
import {
  BATCH_RUNNING_WARNING,
  LeaveConfirmation,
  leaveGuard,
  type LeaveGuarded,
  leaveWarning,
  UNSAVED_CHANGES_WARNING,
  warnBeforeUnload
} from './leave-guard';

@Component({ template: '' })
class FormScreen implements LeaveGuarded {
  readonly dirty = signal(false);
  readonly batchRunning = signal(false);
  readonly leaveWarning = computed(() => leaveWarning({ unsaved: this.dirty(), batchRunning: this.batchRunning() }));

  constructor() {
    warnBeforeUnload(this.leaveWarning);
  }
}

function runGuard(component: Partial<LeaveGuarded> | null) {
  return TestBed.runInInjectionContext(() =>
    leaveGuard(component, {} as ActivatedRouteSnapshot, {} as RouterStateSnapshot, {} as RouterStateSnapshot)
  );
}

function unload(): Event {
  const event = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(event);
  return event;
}

describe('aviso de saída', () => {
  afterEach(() => vi.restoreAllMocks());

  it('escolhe o aviso pelo estado da tela, com o lote em andamento primeiro', () => {
    expect(leaveWarning({})).toBeNull();
    expect(leaveWarning({ unsaved: true })).toBe(UNSAVED_CHANGES_WARNING);
    expect(leaveWarning({ unsaved: true, batchRunning: true })).toBe(BATCH_RUNNING_WARNING);
  });

  it('libera a saída sem aviso e sem a tela implementar o contrato', () => {
    expect(runGuard({ leaveWarning: () => null })).toBe(true);
    expect(runGuard({})).toBe(true);
    expect(runGuard(null)).toBe(true);
  });

  it('pergunta pelo confirm do navegador quando o layout não tem o host', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    await expect(runGuard({ leaveWarning: () => UNSAVED_CHANGES_WARNING })).resolves.toBe(false);
    expect(confirm).toHaveBeenCalledWith(UNSAVED_CHANGES_WARNING);
  });

  it('avisa o navegador ao fechar a aba só enquanto há aviso, e para ao destruir a tela', () => {
    const fixture = TestBed.createComponent(FormScreen);
    expect(unload().defaultPrevented).toBe(false);

    fixture.componentInstance.dirty.set(true);
    expect(unload().defaultPrevented).toBe(true);

    fixture.destroy();
    expect(unload().defaultPrevented).toBe(false);
  });
});

describe('LeaveConfirmHostComponent', () => {
  let fixture: ComponentFixture<LeaveConfirmHostComponent>;
  let confirmation: LeaveConfirmation;

  const button = (text: string) =>
    (Array.from(fixture.nativeElement.querySelectorAll('c-modal-footer button')) as HTMLButtonElement[]).find(
      item => item.textContent?.trim() === text
    ) as HTMLButtonElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [LeaveConfirmHostComponent] }).compileComponents();
    fixture = TestBed.createComponent(LeaveConfirmHostComponent);
    confirmation = TestBed.inject(LeaveConfirmation);
    await fixture.whenStable();
  });

  it('abre o modal com o aviso e resolve a saída em Sair', async () => {
    const confirm = vi.spyOn(window, 'confirm');
    const answer = runGuard({ leaveWarning: () => BATCH_RUNNING_WARNING });
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('c-modal-body')?.textContent).toContain('Há um lote em andamento');
    button('Sair').click();

    await expect(answer).resolves.toBe(true);
    expect(confirm).not.toHaveBeenCalled();
    expect(confirmation.message()).toBeNull();
  });

  it('mantém a pessoa na tela em Continuar na tela', async () => {
    const answer = runGuard({ leaveWarning: () => UNSAVED_CHANGES_WARNING });
    await fixture.whenStable();

    button('Continuar na tela').click();

    await expect(answer).resolves.toBe(false);
  });

  it('recusa a pergunta aberta quando o host sai do layout', async () => {
    const answer = confirmation.ask(UNSAVED_CHANGES_WARNING);
    fixture.destroy();
    await expect(answer).resolves.toBe(false);
  });
});
