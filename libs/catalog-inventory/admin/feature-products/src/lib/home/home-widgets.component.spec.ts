import { ComponentFixture, DeferBlockBehavior, DeferBlockState, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import type { HomeIndicator } from './home-indicators';
import { HomeWidgetsComponent } from './home-widgets.component';

const MONTHS = [
  { month: '2026-10-01', value: 4 },
  { month: '2026-11-01', value: 5 }
];

const INDICATORS: HomeIndicator[] = [
  {
    key: 'active',
    title: 'Produtos ativos',
    color: 'primary',
    value: 1400,
    months: MONTHS,
    links: [{ label: 'Ver os ativos', url: '/loja/produtos', query: { status: 'ACTIVE' } }]
  },
  { key: 'draft', title: 'Em rascunho', color: 'info', value: 5, change: -1, months: MONTHS, links: [] },
  { key: 'outOfStock', title: 'Sem estoque', color: 'warning', value: 1, change: 2, links: [] },
  { key: 'blocked', title: 'Bloqueados', color: 'danger', value: 1, change: 0, months: MONTHS, links: [] }
];

describe('indicadores da página inicial na tela', () => {
  let fixture: ComponentFixture<HomeWidgetsComponent>;

  const text = (element: Element) => element.textContent?.replace(/\s+/g, ' ').trim();
  const widgets = (): HTMLElement[] => Array.from(fixture.nativeElement.querySelectorAll('c-widget-stat-a'));

  const render = async (inputs: Partial<{ indicators: HomeIndicator[]; loading: boolean; problem: object }>) => {
    fixture = TestBed.createComponent(HomeWidgetsComponent);
    fixture.componentRef.setInput('indicators', inputs.indicators ?? []);
    fixture.componentRef.setInput('loading', inputs.loading ?? false);
    fixture.componentRef.setInput('problem', inputs.problem);
    await fixture.whenStable();
  };

  beforeEach(() => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
    TestBed.configureTestingModule({
      providers: [provideRouter([])],
      deferBlockBehavior: DeferBlockBehavior.Manual
    });
  });

  afterEach(() => vi.restoreAllMocks());

  it('mostra os 4 cards com a cor da posição, o valor e a variação só quando ela vem', async () => {
    await render({ indicators: INDICATORS });

    expect(widgets().map(widget => [...widget.classList].find(name => name.startsWith('bg-')))).toEqual([
      'bg-primary',
      'bg-info',
      'bg-warning',
      'bg-danger'
    ]);
    expect(text(widgets()[0])).toContain('1.400');
    expect(text(widgets()[0])).toContain('Produtos ativos');
    expect(text(widgets()[0])).not.toContain('(');
    expect(text(widgets()[1])).toContain('(-1) em relação ao fim do mês anterior');
    expect(text(widgets()[2])).toContain('(+2)');
    expect(text(widgets()[3])).toContain('(0)');
  });

  it('leva o dropdown à lista filtrada e esconde o dropdown sem atalho', async () => {
    await render({ indicators: INDICATORS });

    const [link] = widgets()[0].querySelectorAll<HTMLAnchorElement>('a[cDropdownItem]');
    expect(link.getAttribute('href')).toBe('/loja/produtos?status=ACTIVE');
    expect(text(link)).toBe('Ver os ativos');
    expect(widgets()[0].querySelector('[aria-label="Opções de Produtos ativos"]')).not.toBeNull();
    expect(widgets()[1].querySelector('[cDropdownToggle]')).toBeNull();
  });

  it('desenha o minigráfico só para os cards com série, depois de carregar o Chart.js', async () => {
    await render({ indicators: INDICATORS });

    const blocks = await fixture.getDeferBlocks();
    expect(blocks).toHaveLength(3);
    expect(fixture.nativeElement.querySelector('c-chart')).toBeNull();

    await blocks[0].render(DeferBlockState.Complete);
    const chart = widgets()[0].querySelector('c-chart');
    expect(chart?.getAttribute('aria-label')).toBe('Produtos ativos nos últimos meses');
    expect(widgets()[2].classList).toContain('pb-3');
  });

  it('mostra o carregamento e o erro com Tentar de novo enquanto não há números', async () => {
    await render({ loading: true });
    expect(fixture.nativeElement.querySelector('app-loading-state')).not.toBeNull();

    await render({ problem: { status: 503, detail: 'Serviço fora do ar.' } });
    const retry = vi.fn();
    fixture.componentInstance.retry.subscribe(retry);
    expect(text(fixture.nativeElement)).toContain('Não foi possível carregar os números do catálogo.');
    expect(text(fixture.nativeElement)).toContain('Serviço fora do ar.');
    fixture.nativeElement.querySelector('app-error-state button').click();
    expect(retry).toHaveBeenCalled();
  });

  it('mantém os números da carga anterior quando a nova falha e avisa a falha com Tentar de novo', async () => {
    await render({ indicators: INDICATORS, problem: { status: 503, detail: 'Serviço fora do ar.' } });
    const retry = vi.fn();
    fixture.componentInstance.retry.subscribe(retry);

    expect(widgets()).toHaveLength(4);
    expect(fixture.nativeElement.querySelector('app-error-state')).toBeNull();
    const alert = fixture.nativeElement.querySelector('c-alert') as HTMLElement;
    expect(text(alert)).toContain('Não foi possível atualizar os números do catálogo. Os números abaixo são da última leitura.');
    expect(text(alert)).toContain('Serviço fora do ar.');
    (alert.querySelector('button') as HTMLButtonElement).click();
    expect(retry).toHaveBeenCalled();
  });

  it('não mostra o aviso de falha quando a carga dá certo', async () => {
    await render({ indicators: INDICATORS });

    expect(fixture.nativeElement.querySelector('c-alert')).toBeNull();
  });
});
