import { ComponentFixture, TestBed } from '@angular/core/testing';
import { cilInbox, cilMediaPause } from '@coreui/icons';
import { GENERIC_REASON_MESSAGE } from '@marketplace/shared-web-util';

import {
  type ActionMenuChoice,
  ActionMenuComponent,
  type ActionPresentation,
  serverActionMenu
} from './action-menu.component';

const PRESENTATIONS: readonly ActionPresentation[] = [
  { id: 'SUBMIT', label: 'Enviar para moderação' },
  { id: 'PAUSE', label: 'Pausar', icon: cilMediaPause },
  { id: 'RESUME', label: 'Reativar' },
  { id: 'ARCHIVE', label: 'Arquivar', icon: cilInbox, danger: true }
];

describe('menu de ações vindo do servidor', () => {
  it('mostra as liberadas e as barradas com o motivo, na ordem da tela, e ignora o resto', () => {
    const items = serverActionMenu(
      {
        actions: ['ARCHIVE', 'PAUSE', 'CHANGE_CATEGORY'],
        blockedActions: [
          { action: 'RESUME', reason: { code: 'seller-not-eligible', message: 'A loja ainda não foi aprovada.' } },
          { action: 'SUBMIT' }
        ]
      },
      PRESENTATIONS
    );

    expect(items.map(item => [item.id, item.disabledReason])).toEqual([
      ['SUBMIT', GENERIC_REASON_MESSAGE],
      ['PAUSE', undefined],
      ['RESUME', 'A loja ainda não foi aprovada.'],
      ['ARCHIVE', undefined]
    ]);
    expect(serverActionMenu(undefined, PRESENTATIONS)).toEqual([]);
  });
});

describe('ActionMenuComponent', () => {
  let fixture: ComponentFixture<ActionMenuComponent>;

  const items = () => Array.from(fixture.nativeElement.querySelectorAll('[cDropdownItem]')) as HTMLButtonElement[];
  const toggle = () => fixture.nativeElement.querySelector('[cDropdownToggle]') as HTMLButtonElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [ActionMenuComponent] }).compileComponents();
    fixture = TestBed.createComponent(ActionMenuComponent);
    fixture.componentRef.setInput(
      'items',
      serverActionMenu(
        {
          actions: ['PAUSE', 'ARCHIVE'],
          blockedActions: [{ action: 'RESUME', reason: { message: 'A loja ainda não foi aprovada.' } }]
        },
        PRESENTATIONS
      )
    );
    fixture.componentRef.setInput('label', 'Ações de Camiseta Basic');
    await fixture.whenStable();
  });

  it('desenha o dropdown do template com o nome do botão', () => {
    expect(toggle().getAttribute('aria-label')).toBe('Ações de Camiseta Basic');
    expect(items().map(item => item.textContent?.replace(/\s+/g, ' ').trim())).toEqual([
      'Pausar',
      'Reativar : A loja ainda não foi aprovada.',
      'Arquivar'
    ]);
  });

  it('desabilita a ação barrada e mostra a destrutiva em vermelho', () => {
    expect(items().map(item => item.disabled)).toEqual([false, true, false]);
    expect(items()[2].classList).toContain('text-danger');
  });

  it('emite a ação escolhida com o botão do menu, para o modal devolver o foco', () => {
    const chosen: ActionMenuChoice[] = [];
    fixture.componentInstance.action.subscribe(choice => chosen.push(choice));

    items()[2].click();

    expect(chosen).toEqual([{ id: 'ARCHIVE', toggle: toggle() }]);
  });

  it('some sem ações', async () => {
    fixture.componentRef.setInput('items', []);
    await fixture.whenStable();
    expect(toggle()).toBeNull();
  });
});
