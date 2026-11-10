import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import {
  type ImportResponse,
  provideCatalogInventoryApi,
} from '@marketplace/catalog-inventory-admin-data-access';

import { ImportBatchPageComponent } from './import-batch-page.component';
import { POLL_INTERVAL_MS } from './import-tracking';
import { API, importProgress, importReport, settle } from './testing';

const B1 = `${API}/products/imports/b1`;
const B1_PROGRESS = `${B1}?lines=false`;

describe('ImportBatchPageComponent', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;

  const text = () => (harness.routeNativeElement?.textContent ?? '').replace(/\s+/g, ' ');
  const button = (label: string) =>
    (
      Array.from(
        harness.routeNativeElement?.querySelectorAll('button') ?? [],
      ) as HTMLButtonElement[]
    ).find((item) => item.textContent?.trim() === label) as HTMLButtonElement;

  async function open(): Promise<void> {
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/loja/importacoes/b1');
    await settle();
  }

  /** Responde a leitura do andamento, sem as linhas, ou a de `url`. */
  async function answer(
    body: object | null,
    init?: Parameters<ReturnType<HttpTestingController['expectOne']>['flush']>[1],
    url = B1_PROGRESS,
  ) {
    http.expectOne(url).flush(body, init);
    await settle();
    render();
  }

  /** Responde o andamento já no status terminal e a leitura final, com as linhas. */
  async function answerDone(report: ImportResponse) {
    await answer({ ...report, lines: null });
    await answer(report, undefined, B1);
  }

  function render(): void {
    // NOTE: a barra de progresso do CoreUI passa o valor por um efeito, que só aparece na passada seguinte.
    harness.detectChanges();
    harness.detectChanges();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'loja/importacoes/:id', component: ImportBatchPageComponent }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideCatalogInventoryApi(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => vi.useRealTimers());

  it('mostra o lote em andamento com a situação, o andamento e os números, sem as linhas', async () => {
    await open();
    expect(harness.routeNativeElement?.querySelector('app-loading-state')).not.toBeNull();

    await answer(
      importProgress('PROCESSING', {
        processed: 300,
        total: 1200,
        lines: [
          { lineNumber: 2, sellerSku: 'CAM-P', outcome: 'CREATED', productId: 'p1' },
          {
            lineNumber: 3,
            sellerSku: 'CAM-PP',
            outcome: 'FAILED',
            code: 'invalid-row',
            message: 'weight_g inválido',
          },
        ],
      }),
    );

    expect(harness.routeNativeElement?.querySelector('h1')?.textContent?.trim()).toBe(
      'Resultado da importação',
    );
    expect(text()).toContain('Lote de 08/11/2026 14:20');
    expect(text()).toContain('Processando');
    expect(text()).toContain('300 de 1200 linhas');
    const progress = harness.routeNativeElement?.querySelector('c-progress');
    expect(progress?.getAttribute('aria-valuenow')).toBe('25');
    expect(progress?.getAttribute('aria-valuemax')).toBe('100');
    const blocks = Array.from(
      harness.routeNativeElement?.querySelectorAll('app-number-block') ?? [],
    ).map((block) =>
      Array.from(block.querySelectorAll('.small, .fs-5')).map((part) => part.textContent?.trim()),
    );
    expect(blocks).toEqual([
      ['Criadas', '1'],
      ['Atualizadas', '0'],
      ['Sem mudança', '0'],
      ['Falhas', '1'],
    ]);
    expect(harness.routeNativeElement?.querySelector('app-import-lines')).toBeNull();
    expect(harness.routeNativeElement?.querySelector('h2 a')).toBeNull();
  });

  it('mostra as linhas do lote terminado, lidas uma vez no fim', async () => {
    await open();
    await answerDone(
      importReport('COMPLETED', {
        total: 2,
        lines: [
          { lineNumber: 2, sellerSku: 'CAM-P', outcome: 'CREATED', productId: 'p1' },
          {
            lineNumber: 3,
            sellerSku: 'CAM-PP',
            outcome: 'FAILED',
            code: 'invalid-row',
            message: 'weight_g inválido',
          },
        ],
      }),
    );

    expect(text()).toContain('Concluída');
    expect(text()).toContain('weight_g inválido');
    expect(text()).not.toContain('invalid-row');
    http.verify();
  });

  it('consulta de novo a cada 2 segundos até o lote terminar', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    await open();
    await answer(importProgress('PENDING', { processed: 0 }));
    expect(text()).toContain('Na fila');

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    await settle();
    await answerDone(importReport('COMPLETED', { processed: 100 }));
    expect(text()).toContain('Concluída');
    expect(text()).toContain('100 de 100 linhas');

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3);
    await settle();
    http.verify();
  });

  it('mostra o erro do servidor com Tentar de novo quando o lote não abre', async () => {
    await open();
    await answer(
      {
        type: 'https://marketplace.dev/problems/not-found',
        title: 'Não encontrado',
        status: 404,
        detail: 'Importação não encontrada',
      },
      { status: 404, statusText: 'Not Found' },
    );

    expect(text()).toContain('Não foi possível carregar a importação.');
    expect(text()).toContain('Importação não encontrada');

    button('Tentar de novo').click();
    await settle();
    await answerDone(importReport('COMPLETED', { processed: 100 }));
    expect(text()).toContain('Concluída');
  });

  it('avisa quando a consulta do andamento falha e mantém o que já foi lido', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    await open();
    await answer(importProgress('PROCESSING', { processed: 30 }));

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    await settle();
    await answer(null, { status: 0, statusText: 'Unknown Error' });

    expect(text()).toContain('Não foi possível atualizar o andamento.');
    expect(text()).toContain('Não foi possível falar com o servidor.');
    expect(text()).toContain('30 de 100 linhas');

    button('Tentar de novo').click();
    await settle();
    await answerDone(importReport('COMPLETED', { processed: 100 }));
    expect(text()).not.toContain('Não foi possível atualizar o andamento.');
  });

  it('para de consultar ao sair da tela', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    await open();
    await answer(importProgress('PROCESSING', { processed: 30 }));

    harness.fixture.destroy();
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3);
    await settle();
    http.verify();
  });
});
