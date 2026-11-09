import { ComponentFixture, TestBed } from '@angular/core/testing';

import { EditConflictAlertComponent } from './edit-conflict-alert.component';
import { ProblemAlertComponent } from './problem-alert.component';

const TYPE = 'https://marketplace.dev/problems/';

describe('ProblemAlertComponent', () => {
  let fixture: ComponentFixture<ProblemAlertComponent>;

  const texts = (selector: string) =>
    (Array.from(fixture.nativeElement.querySelectorAll(selector)) as HTMLElement[]).map(item => item.textContent?.trim());

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [ProblemAlertComponent] }).compileComponents();
    fixture = TestBed.createComponent(ProblemAlertComponent);
  });

  it('não mostra nada sem problema', async () => {
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('c-alert')).toBeNull();
  });

  it('mostra a mensagem de cada motivo vinda do servidor', async () => {
    fixture.componentRef.setInput('problem', {
      type: `${TYPE}product-not-publishable`,
      status: 422,
      reasons: ['missing-attribute:material', 'missing-offer:CAM-P'],
      reasonDetails: [
        { code: 'missing-attribute', params: ['material'], message: 'Preencha o atributo Material.' },
        { code: 'missing-offer', params: ['CAM-P'], message: 'Defina o preço do SKU CAM-P.' }
      ]
    });
    await fixture.whenStable();

    expect(texts('li')).toEqual(['Preencha o atributo Material.', 'Defina o preço do SKU CAM-P.']);
  });

  it('mostra a mensagem do servidor para o motivo de moderação fora do catálogo', async () => {
    const message = 'Um dos motivos escolhidos não está no catálogo de motivos. Recarregue a lista de motivos.';
    fixture.componentRef.setInput('problem', {
      type: `${TYPE}invalid-moderation-reason`,
      status: 422,
      reasons: ['unknown-reason:spam'],
      reasonDetails: [{ code: 'unknown-reason', params: ['spam'], message }]
    });
    await fixture.whenStable();

    expect(texts('p')).toEqual([message]);
  });

  it('mostra o detail do problema e os campos inválidos', async () => {
    fixture.componentRef.setInput('problem', {
      type: `${TYPE}validation-failed`,
      title: 'Dados inválidos',
      status: 400,
      detail: 'Corrija os campos indicados.',
      errors: [{ field: 'title', message: 'não deve estar em branco' }]
    });
    fixture.componentRef.setInput('fieldLabels', { title: 'Título' });
    fixture.componentRef.setInput('heading', 'Não foi possível salvar');
    await fixture.whenStable();

    expect(texts('p')).toEqual(['Não foi possível salvar', 'Corrija os campos indicados.']);
    expect(texts('li')).toEqual(['Título: não deve estar em branco']);
  });

  it('lista o erro sem campo só com a mensagem e deixa de fora o erro sem mensagem', async () => {
    fixture.componentRef.setInput('problem', {
      type: `${TYPE}validation-failed`,
      status: 400,
      detail: 'Corrija os campos indicados.',
      errors: [{ field: 'title' }, { message: 'Confira os dados.' }, { field: 'title', message: 'obrigatório' }]
    });
    fixture.componentRef.setInput('fieldLabels', { title: 'Título' });
    await fixture.whenStable();

    expect(texts('li')).toEqual(['Confira os dados.', 'Título: obrigatório']);
    expect(fixture.nativeElement.querySelectorAll('li strong').length).toBe(1);
  });

  it('cai no title e nunca mostra o código cru', async () => {
    fixture.componentRef.setInput('problem', { type: `${TYPE}brand-new`, title: 'Recusa nova', status: 409 });
    await fixture.whenStable();
    expect(texts('p')).toEqual(['Recusa nova']);

    fixture.componentRef.setInput('problem', { type: `${TYPE}brand-new`, status: 409 });
    await fixture.whenStable();
    expect(texts('p')).toEqual(['Não foi possível concluir a operação.']);
  });
});

describe('EditConflictAlertComponent', () => {
  it('avisa do conflito e pede a versão atual', async () => {
    await TestBed.configureTestingModule({ imports: [EditConflictAlertComponent] }).compileComponents();
    const fixture = TestBed.createComponent(EditConflictAlertComponent);
    let reloads = 0;
    fixture.componentInstance.reload.subscribe(() => reloads++);
    await fixture.whenStable();

    const element: HTMLElement = fixture.nativeElement;
    expect(element.textContent).toContain('Outra pessoa alterou este registro depois que você abriu a tela');
    const button = element.querySelector('button') as HTMLButtonElement;
    expect(button.textContent?.trim()).toBe('Carregar versão atual');
    button.click();
    expect(reloads).toBe(1);
  });
});
