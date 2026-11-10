import {
  ComponentFixture,
  DeferBlockBehavior,
  DeferBlockState,
  TestBed,
} from '@angular/core/testing';
import type { ActivityResponse } from '@marketplace/catalog-inventory-admin-data-access';

import { CatalogActivityCardComponent } from './catalog-activity-card.component';

const ACTIVITY: ActivityResponse = {
  granularity: 'month',
  from: '2026-01-01',
  to: '2026-12-31',
  timeZone: 'America/Sao_Paulo',
  points: [
    { period: '2026-10-01', submitted: 3, approved: 2, rejected: 1, blocked: 0 },
    { period: '2026-11-01', submitted: 1, approved: 4, rejected: 0, blocked: 1 },
  ],
};

describe('card Catálogo da página inicial', () => {
  let fixture: ComponentFixture<CatalogActivityCardComponent>;

  const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim();
  const element = <T extends Element>(selector: string) =>
    fixture.nativeElement.querySelector(selector) as T;

  const render = async (activity?: ActivityResponse, counts?: object) => {
    fixture = TestBed.createComponent(CatalogActivityCardComponent);
    fixture.componentRef.setInput('activity', activity);
    fixture.componentRef.setInput('counts', counts);
    await fixture.whenStable();
  };

  beforeEach(() => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
    TestBed.configureTestingModule({ deferBlockBehavior: DeferBlockBehavior.Manual });
  });

  afterEach(() => vi.restoreAllMocks());

  it('mostra o título, o período da série e o gráfico depois de carregar o Chart.js', async () => {
    await render(ACTIVITY);

    expect(text(element('h2'))).toBe('Catálogo');
    expect(text(element('.small.text-body-secondary'))).toBe('Janeiro a dezembro de 2026');
    expect(element('c-chart')).toBeNull();

    const [chart] = await fixture.getDeferBlocks();
    await chart.render(DeferBlockState.Complete);
    expect(element('c-chart').getAttribute('aria-label')).toContain(
      'Produtos aprovados e enviados à moderação',
    );
  });

  it('troca a granularidade pelo grupo Dia, Mês e Ano', async () => {
    await render(ACTIVITY);
    const radios = Array.from(
      fixture.nativeElement.querySelectorAll('input.btn-check'),
    ) as HTMLInputElement[];

    expect(radios.map((radio) => [radio.value, radio.checked])).toEqual([
      ['day', false],
      ['month', true],
      ['year', false],
    ]);
    expect(
      Array.from(fixture.nativeElement.querySelectorAll('label.btn')).map((label) =>
        text(label as Element),
      ),
    ).toEqual(['Dia', 'Mês', 'Ano']);

    radios[0].click();
    expect(fixture.componentInstance.granularity()).toBe('day');
  });

  it('baixa os números da série em CSV', async () => {
    await render(ACTIVITY);
    const blobs: Blob[] = [];
    const createObjectURL = vi.fn((blob: Blob) => {
      blobs.push(blob);
      return 'blob:catalogo';
    });
    const revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', Object.assign(Object.create(URL), { createObjectURL, revokeObjectURL }));
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      expect(this.download).toBe('catalogo-mes-2026-01-01-a-2026-12-31.csv');
      expect(this.href).toBe('blob:catalogo');
    });

    element<HTMLButtonElement>('button[aria-label="Baixar os números em CSV"]').click();

    expect(click).toHaveBeenCalledOnce();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:catalogo');
    const csv = await new Promise<string>((resolve) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.readAsText(blobs[0]);
    });
    expect(csv).toContain(
      'Período,Enviados à moderação,Aprovados,Rejeitados,Bloqueados\r\n2026-10-01,3,2,1,0',
    );
    vi.unstubAllGlobals();
  });

  it('mostra o rodapé com os 5 números e a parte de cada um quando há contagem da loja', async () => {
    await render(ACTIVITY, {
      active: 14,
      draft: 5,
      pendingModeration: 1,
      blocked: 1,
      pausedBySeller: 1,
      total: 26,
    });

    const columns = Array.from(
      fixture.nativeElement.querySelectorAll('c-card-footer c-col'),
    ) as HTMLElement[];
    expect(
      columns.map((column) => Array.from(column.querySelectorAll('div'), text).join(' ')),
    ).toEqual([
      'Ativos 14 produtos (54%)',
      'Em rascunho 5 produtos (19%)',
      'Em moderação 1 produto (4%)',
      'Bloqueados 1 produto (4%)',
      'Pausados 1 produto (4%)',
    ]);
    expect(columns[4].classList).toContain('d-none');
    expect(columns[0].querySelector('c-progress')?.getAttribute('aria-valuenow')).toBe('54');
  });

  it('fica sem rodapé fora do conjunto da loja e com o carregamento enquanto a série não chega', async () => {
    await render();

    expect(element('c-card-footer')).toBeNull();
    expect(element('app-loading-state')).not.toBeNull();
    expect(
      element<HTMLButtonElement>('button[aria-label="Baixar os números em CSV"]').disabled,
    ).toBe(true);
  });
});
