import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { ImportLineResponse } from '@marketplace/catalog-inventory-admin-data-access';

import { ImportLinesComponent } from './import-lines.component';

const LINES: ImportLineResponse[] = [
  { lineNumber: 2, sellerSku: 'CAM-P', outcome: 'CREATED', productId: 'p1' },
  { lineNumber: 3, sellerSku: 'CAM-M', outcome: 'UNCHANGED', productId: 'p1' },
  {
    lineNumber: 14,
    sellerSku: 'CAM-PP',
    outcome: 'FAILED',
    code: 'invalid-product',
    message: 'Produto inválido',
    reasonDetails: [
      { code: 'missing-variant-attribute', params: ['CAM-PP', 'cor'], message: 'O SKU CAM-PP não tem o atributo Cor' }
    ]
  },
  { lineNumber: 15, outcome: 'FAILED', code: 'group-rejected', message: 'Produto P1 não criado por erro na(s) linha(s) 14' }
];

describe('ImportLinesComponent', () => {
  let fixture: ComponentFixture<ImportLinesComponent>;

  const rows = () => Array.from(fixture.nativeElement.querySelectorAll('tbody tr')) as HTMLTableRowElement[];
  const cells = (row: HTMLTableRowElement) => Array.from(row.cells).map(cell => cell.textContent?.trim().replace(/\s+/g, ' '));
  const radio = (label: string) => {
    const element = (Array.from(fixture.nativeElement.querySelectorAll('label')) as HTMLLabelElement[]).find(
      item => item.textContent?.trim() === label
    );
    return fixture.nativeElement.querySelector(`#${element?.htmlFor}`) as HTMLInputElement;
  };
  const button = (text: string) =>
    (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[]).find(
      item => item.textContent?.trim() === text
    ) as HTMLButtonElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    fixture = TestBed.createComponent(ImportLinesComponent);
    fixture.componentRef.setInput('lines', LINES);
    await fixture.whenStable();
  });

  it('lista cada linha com o SKU, o resultado e a mensagem do servidor, sem o código cru', () => {
    expect(rows().map(row => cells(row).slice(0, 3))).toEqual([
      ['2', 'CAM-P', 'Criada'],
      ['3', 'CAM-M', 'Sem mudança'],
      ['14', 'CAM-PP', 'Falha'],
      ['15', 'Sem SKU', 'Falha']
    ]);
    expect(cells(rows()[0])[3]).toBe('');
    expect(cells(rows()[3])[3]).toBe('Produto P1 não criado por erro na(s) linha(s) 14');
    expect(fixture.nativeElement.textContent).not.toContain('invalid-product');
    expect(fixture.nativeElement.textContent).not.toContain('missing-variant-attribute');
    expect(rows()[2].cells[3].querySelector('div')?.textContent).toBe('Produto inválido');
    expect(Array.from(rows()[2].querySelectorAll('li')).map(item => item.textContent?.trim())).toEqual([
      'O SKU CAM-PP não tem o atributo Cor'
    ]);
  });

  it('leva ao produto da linha pelo productId', () => {
    const link: HTMLAnchorElement = rows()[0].querySelector('a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/loja/produtos/p1');
    expect(rows()[2].querySelector('a')).toBeNull();
  });

  it('filtra só as falhas e volta para todas', async () => {
    radio('Só falhas').click();
    await fixture.whenStable();
    expect(rows().map(row => row.cells[0].textContent?.trim())).toEqual(['14', '15']);
    expect(radio('Só falhas').checked).toBe(true);

    radio('Todas').click();
    await fixture.whenStable();
    expect(rows()).toHaveLength(4);
  });

  it('avisa quando não há falha no filtro de falhas', async () => {
    fixture.componentRef.setInput('lines', LINES.slice(0, 2));
    radio('Só falhas').click();
    await fixture.whenStable();
    expect(rows()).toHaveLength(0);
    expect(fixture.nativeElement.textContent).toContain('Nenhuma linha com falha.');
  });

  it('divide as linhas em páginas de 50 com Anterior e Próxima', async () => {
    const many: ImportLineResponse[] = Array.from({ length: 60 }, (_, index) => ({
      lineNumber: index + 2,
      sellerSku: `SKU-${index}`,
      outcome: index === 55 ? 'FAILED' : 'UPDATED',
      message: index === 55 ? 'Preço inválido' : null
    }));
    fixture.componentRef.setInput('lines', many);
    await fixture.whenStable();
    expect(rows()).toHaveLength(50);
    expect(button('Anterior').disabled).toBe(true);

    button('Próxima').click();
    await fixture.whenStable();
    expect(rows()).toHaveLength(10);
    expect(rows()[0].cells[0].textContent?.trim()).toBe('52');
    expect(button('Próxima').disabled).toBe(true);

    radio('Só falhas').click();
    await fixture.whenStable();
    expect(rows().map(row => row.cells[0].textContent?.trim())).toEqual(['57']);
    expect(fixture.nativeElement.querySelector('app-cursor-pagination')).toBeNull();
  });
});
