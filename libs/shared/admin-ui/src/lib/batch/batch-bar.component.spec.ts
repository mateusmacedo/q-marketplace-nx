import { ComponentFixture, TestBed } from '@angular/core/testing';

import { type BatchAction, BatchBarComponent } from './batch-bar.component';
import { BatchResultAlertComponent } from './batch-result-alert.component';

describe('BatchBarComponent', () => {
  let fixture: ComponentFixture<BatchBarComponent>;
  const actions: BatchAction[] = [
    { id: 'pause', label: 'Pausar', runningLabel: 'Pausando', count: 2 },
    { id: 'resume', label: 'Reativar', runningLabel: 'Reativando', count: 0 },
    { id: 'archive', label: 'Arquivar', runningLabel: 'Arquivando', count: 3, color: 'danger' }
  ];

  const buttons = () => Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [BatchBarComponent] }).compileComponents();
    fixture = TestBed.createComponent(BatchBarComponent);
    fixture.componentRef.setInput('selectedCount', 3);
    fixture.componentRef.setInput('actions', actions);
    await fixture.whenStable();
  });

  it('mostra a seleção e quantos itens aceitam cada ação', () => {
    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('[role="toolbar"]')?.textContent).toContain('3 selecionados');
    expect(buttons().map(button => button.textContent?.trim())).toEqual([
      'Pausar (2)',
      'Reativar (0)',
      'Arquivar (3)',
      'Limpar seleção'
    ]);
    expect(buttons().map(button => button.disabled)).toEqual([false, true, false, false]);
  });

  it('emite a ação e a limpeza da seleção', () => {
    const runs: string[] = [];
    let cleared = 0;
    fixture.componentInstance.run.subscribe(id => runs.push(id));
    fixture.componentInstance.clear.subscribe(() => cleared++);

    buttons()[2].click();
    buttons()[3].click();

    expect(runs).toEqual(['archive']);
    expect(cleared).toBe(1);
  });

  it('trava a barra e mostra o spinner na ação em execução', async () => {
    fixture.componentRef.setInput('running', 'pause');
    await fixture.whenStable();

    expect(buttons().every(button => button.disabled)).toBe(true);
    expect(buttons()[0].querySelector('c-spinner')).toBeTruthy();
    expect(buttons()[0].textContent?.trim()).toBe('Pausando…');
    expect(fixture.nativeElement.querySelector('span.visually-hidden[role="status"]')?.textContent).toContain('Pausando 3 selecionados');
  });

  it('some sem seleção', async () => {
    fixture.componentRef.setInput('selectedCount', 0);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[role="toolbar"]')).toBeNull();
  });
});

describe('BatchResultAlertComponent', () => {
  it('lista cada item com falha e o motivo', async () => {
    await TestBed.configureTestingModule({ imports: [BatchResultAlertComponent] }).compileComponents();
    const fixture = TestBed.createComponent(BatchResultAlertComponent);
    fixture.componentRef.setInput('failures', [
      { id: 'p1', label: 'Camiseta Basic', messages: ['A loja ainda não foi aprovada no KYB/KYC.'] }
    ]);
    await fixture.whenStable();

    const items = Array.from(fixture.nativeElement.querySelectorAll('li')) as HTMLElement[];
    expect(items.map(item => item.textContent?.trim())).toEqual([
      'Camiseta Basic: A loja ainda não foi aprovada no KYB/KYC.'
    ]);
  });
});
