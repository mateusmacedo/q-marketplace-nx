import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl, ReactiveFormsModule } from '@angular/forms';

import { buildCategoryOptions, categoryDescendants, type CategoryNode } from './category-select/category-options';
import { CATEGORY_ROOT, CategorySelectComponent } from './category-select/category-select.component';
import { productStatusLabel } from './product-status/product-status';
import { ProductStatusBadgeComponent } from './product-status/product-status-badge.component';
import { type ChecklistItem, issueTarget } from './submission/publishing-issues';
import { SubmissionChecklistComponent } from './submission/submission-checklist.component';

const VEST = { id: 'vest', name: 'Vestuário' };
const CAMISAS = { id: 'camisas', name: 'Camisas' };
const nodes: CategoryNode[] = [
  { id: 'calcas', name: 'Calças', ancestors: [VEST], level: 2, usable: true, childCount: 0 },
  { id: 'vest', name: 'Vestuário', ancestors: [], level: 1, usable: true, childCount: 2 },
  { id: 'camisas', name: 'Camisas', ancestors: [VEST], level: 2, usable: false, childCount: 1 },
  { id: 'polo', name: 'Polo', ancestors: [VEST, CAMISAS], level: 3, usable: false, childCount: 0 },
  { id: 'casa', name: 'Casa', ancestors: [], level: 1, usable: true, childCount: 0 }
];

describe('ProductStatusBadgeComponent', () => {
  it('mostra o rótulo no badge sólido da cor do status', async () => {
    const fixture = TestBed.createComponent(ProductStatusBadgeComponent);
    fixture.componentRef.setInput('status', 'PENDING_MODERATION');
    await fixture.whenStable();

    const badge = fixture.nativeElement.querySelector('c-badge') as HTMLElement;
    expect(badge.textContent?.trim()).toBe('Em moderação');
    expect(badge.className).toContain('info');
  });

  it('traduz todos os status da proposta', () => {
    expect(
      ['DRAFT', 'PENDING_MODERATION', 'ACTIVE', 'PAUSED_BY_SELLER', 'OUT_OF_STOCK', 'BLOCKED', 'ARCHIVED'].map(productStatusLabel)
    ).toEqual(['Rascunho', 'Em moderação', 'Ativo', 'Pausado', 'Sem estoque', 'Bloqueado', 'Arquivado']);
    expect(productStatusLabel('NEW_STATUS')).toBe('NEW_STATUS');
  });
});

describe('opções de categoria', () => {
  it('monta o caminho pelos ancestrais do servidor, na ordem da árvore', () => {
    expect(buildCategoryOptions(nodes)).toEqual([
      { id: 'casa', path: 'Casa', depth: 1, usable: true, childCount: 0 },
      { id: 'vest', path: 'Vestuário', depth: 1, usable: true, childCount: 2 },
      { id: 'calcas', path: 'Vestuário › Calças', depth: 2, usable: true, childCount: 0 },
      { id: 'camisas', path: 'Vestuário › Camisas', depth: 2, usable: false, childCount: 1 },
      { id: 'polo', path: 'Vestuário › Camisas › Polo', depth: 3, usable: false, childCount: 0 }
    ]);
  });

  it('usa a usabilidade e o nível do servidor, sem recalcular pelos ancestrais', () => {
    const [option] = buildCategoryOptions([{ id: 'x', name: 'Solta', ancestors: [CAMISAS], level: 7, usable: true }]);
    expect(option).toEqual({ id: 'x', path: 'Camisas › Solta', depth: 7, usable: true, childCount: undefined });
  });

  it('mantém juntas as subcategorias de irmãs com o mesmo nome', () => {
    const a = { id: 'a', name: 'Moda' };
    const b = { id: 'b', name: 'Moda' };
    const options = buildCategoryOptions([
      { id: 'b1', name: 'Bolsas', ancestors: [b] },
      { id: 'a', name: 'Moda', ancestors: [] },
      { id: 'a1', name: 'Anéis', ancestors: [a] },
      { id: 'b', name: 'Moda', ancestors: [] }
    ]);
    expect(options.map(option => option.id)).toEqual(['a', 'a1', 'b', 'b1']);
  });

  it('lista as descendentes da categoria', () => {
    expect(categoryDescendants(nodes, 'vest').sort()).toEqual(['calcas', 'camisas', 'polo']);
    expect(categoryDescendants(nodes, 'casa')).toEqual([]);
  });
});

@Component({
  imports: [CategorySelectComponent, ReactiveFormsModule],
  template: `
    <app-category-select [(value)]="value" [excluded]="['vest']" [options]="options" rootLabel="Raiz" />
    <app-category-select [formControl]="control" [options]="options" />
  `
})
class SelectHost {
  readonly options = buildCategoryOptions(nodes);
  readonly value = signal<string | null>(null);
  readonly control = new FormControl<string | null>('calcas');
}

describe('CategorySelectComponent', () => {
  let fixture: ComponentFixture<SelectHost>;
  const selects = () => Array.from(fixture.nativeElement.querySelectorAll('select')) as HTMLSelectElement[];

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [SelectHost] }).compileComponents();
    fixture = TestBed.createComponent(SelectHost);
    await fixture.whenStable();
  });

  it('lista os caminhos sem as exclusões, com a raiz e a marca de inativa', () => {
    const texts = Array.from(selects()[0].options).map(option => option.textContent?.trim());
    expect(texts).toEqual([
      'Escolha a categoria',
      'Raiz',
      'Casa',
      'Vestuário › Calças',
      'Vestuário › Camisas (inativa)',
      'Vestuário › Camisas › Polo (inativa)'
    ]);
  });

  it('atualiza o valor ligado e escolhe a raiz', async () => {
    const select = selects()[0];
    select.value = 'calcas';
    select.dispatchEvent(new Event('change'));
    expect(fixture.componentInstance.value()).toBe('calcas');

    select.value = CATEGORY_ROOT;
    select.dispatchEvent(new Event('change'));
    expect(fixture.componentInstance.value()).toBe(CATEGORY_ROOT);
  });

  it('funciona com formulário reativo', async () => {
    const select = selects()[1];
    expect(select.value).toBe('calcas');

    select.value = 'casa';
    select.dispatchEvent(new Event('change'));
    expect(fixture.componentInstance.control.value).toBe('casa');

    fixture.componentInstance.control.disable();
    await fixture.whenStable();
    expect(select.disabled).toBe(true);
  });
});

describe('SubmissionChecklistComponent', () => {
  let fixture: ComponentFixture<SubmissionChecklistComponent>;
  const issues = [
    { code: 'missing-offer', params: ['CAM-P'], message: 'Defina o preço do SKU CAM-P.', field: 'skus[CAM-P].offer' },
    { code: 'missing-attribute', params: ['material'], message: 'Preencha o atributo Material.', field: 'attributes.material' },
    { code: 'novo-motivo', field: 'skus[CAM-P].variantAttributes.cor' }
  ];

  beforeEach(async () => {
    fixture = TestBed.createComponent(SubmissionChecklistComponent);
    fixture.componentRef.setInput('issues', issues);
    await fixture.whenStable();
  });

  const items = () =>
    (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLElement[]).map(item => item.textContent?.trim());

  it('lista as pendências com o texto do servidor e nunca o código cru', () => {
    expect(items()).toEqual([
      'Defina o preço do SKU CAM-P.',
      'Preencha o atributo Material.',
      'Não foi possível concluir a operação.'
    ]);
  });

  it('leva à aba e ao campo tirados do caminho que o servidor devolve', () => {
    const chosen: ChecklistItem[] = [];
    fixture.componentInstance.go.subscribe(item => chosen.push(item));
    for (const button of Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[]) {
      button.click();
    }
    expect(chosen.map(item => [item.tab, item.sellerSku, item.field])).toEqual([
      ['skus', 'CAM-P', 'offer'],
      ['conteudo', undefined, 'attributes.material'],
      ['skus', 'CAM-P', 'variantAttributes.cor']
    ]);
  });

  it('mostra a conferência em andamento e o rodapé da recusa do envio', async () => {
    fixture.componentRef.setInput('loading', true);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('c-spinner')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('c-card-footer')?.textContent).toContain('Conferindo o rascunho');

    fixture.componentRef.setInput('loading', false);
    fixture.componentRef.setInput('refused', true);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('c-spinner')).toBeNull();
    expect(fixture.nativeElement.querySelector('c-card-footer')?.textContent).toContain('Motivos da recusa');
  });

  it('avisa quando está tudo pronto, mas não durante a conferência', async () => {
    fixture.componentRef.setInput('issues', []);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Tudo pronto para enviar.');

    fixture.componentRef.setInput('loading', true);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).not.toContain('Tudo pronto para enviar.');
  });
});

describe('caminho das pendências', () => {
  it('separa a aba, o SKU e o campo', () => {
    expect(issueTarget('categoryId')).toEqual({ tab: 'conteudo', field: 'categoryId' });
    expect(issueTarget('skus')).toEqual({ tab: 'skus' });
    expect(issueTarget('skus[CAM-P].variantAttributes')).toEqual({ tab: 'skus', sellerSku: 'CAM-P', field: 'variantAttributes' });
    expect(issueTarget(undefined)).toEqual({ tab: 'conteudo' });
  });
});
