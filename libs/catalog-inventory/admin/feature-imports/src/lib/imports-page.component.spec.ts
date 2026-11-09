import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, type TestRequest } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import {
  type ImportPage,
  type ImportSummaryResponse,
  provideCatalogInventoryApi
} from '@marketplace/catalog-inventory-admin-data-access';
import { ToastService } from '@marketplace/shared-admin-ui';

import { ImportBatchPageComponent } from './import-batch-page.component';
import { ImportsPageComponent } from './imports-page.component';
import { API, importReport, settle } from './testing';

const LIST = `${API}/products/imports`;

function summary(id: string, status: ImportSummaryResponse['status'], failed = 0): ImportSummaryResponse {
  return {
    id,
    status,
    totalLines: 800,
    processedLines: status === 'COMPLETED' ? 800 : 200,
    counts: { created: 10, updated: 0, unchanged: 0, failed },
    createdAt: '2026-11-07T12:02:00Z'
  };
}

describe('ImportsPageComponent', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;

  const page = () => harness.routeNativeElement as HTMLElement;
  const text = () => (page().textContent ?? '').replace(/\s+/g, ' ');
  const button = (label: string) =>
    (Array.from(page().querySelectorAll('button')) as HTMLButtonElement[]).find(
      item => item.textContent?.replace(/\s+/g, ' ').trim() === label
    ) as HTMLButtonElement;
  const historyRows = () =>
    Array.from(page().querySelectorAll('c-card:last-of-type tbody tr')) as HTMLTableRowElement[];

  async function open(url = '/loja/importacoes'): Promise<void> {
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    await settle();
  }

  function list(): TestRequest {
    return http.expectOne(request => request.method === 'GET' && request.url.startsWith(LIST) && !request.url.startsWith(`${LIST}/`));
  }

  async function answer(request: TestRequest, body: object | null, init?: Parameters<TestRequest['flush']>[1]) {
    request.flush(body, init);
    await settle();
    harness.detectChanges();
    harness.detectChanges();
  }

  async function choose(file: File): Promise<void> {
    const input = page().querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, 'files', { configurable: true, value: [file] });
    input.dispatchEvent(new Event('change'));
    await settle();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'loja/importacoes', component: ImportsPageComponent },
          { path: 'loja/importacoes/:id', component: ImportBatchPageComponent }
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideCatalogInventoryApi()
      ]
    });
    http = TestBed.inject(HttpTestingController);
  });

  it('lista o histórico do mais recente ao mais antigo, com o lote como link', async () => {
    await open();
    const request = list();
    expect(request.request.urlWithParams).toBe(`${LIST}?limit=20`);
    expect(page().querySelector('app-loading-state')).not.toBeNull();

    await answer(request, { items: [summary('b2', 'PROCESSING'), summary('b1', 'COMPLETED', 3)], nextCursor: 'c2' } satisfies ImportPage);

    expect(page().querySelector('h1')?.textContent?.trim()).toBe('Importações');
    const rows = historyRows();
    expect(rows.map(row => Array.from(row.cells).slice(1, 4).map(cell => cell.textContent?.replace(/\s+/g, ' ').trim()))).toEqual([
      ['Processando', '200 de 800 linhas', '0'],
      ['Concluída', '800 linhas', '3']
    ]);
    expect(rows[1].querySelector('a')?.getAttribute('href')).toBe('/loja/importacoes/b1');
    expect(rows[1].cells[0].textContent).toContain('07/11/2026 09:02');
  });

  it('acompanha no topo o lote mais recente em andamento ao abrir a tela e recarrega o histórico no fim', async () => {
    await open();
    await answer(list(), { items: [summary('b2', 'PROCESSING'), summary('b1', 'COMPLETED')] });
    const tracked = await vi.waitFor(async () => {
      await settle();
      harness.detectChanges();
      return http.expectOne(`${LIST}/b2?lines=false`);
    });
    const done = importReport('COMPLETED', {
      id: 'b2',
      total: 1,
      lines: [{ lineNumber: 2, sellerSku: 'CAM-P', outcome: 'CREATED', productId: 'p1' }]
    });
    await answer(tracked, { ...done, lines: null });
    await answer(http.expectOne(`${LIST}/b2`), done);

    expect(page().querySelector('app-import-batch h2 a')?.getAttribute('href')).toBe('/loja/importacoes/b2');
    await settle();
    await answer(list(), { items: [summary('b2', 'COMPLETED'), summary('b1', 'COMPLETED')] });
    expect(page().querySelector('app-import-batch')).not.toBeNull();
    http.verify();
  });

  it('pagina o histórico pelo cursor, que fica na URL', async () => {
    await open();
    await answer(list(), { items: [summary('b1', 'COMPLETED')], nextCursor: 'c2' });

    button('Próxima').click();
    await settle();
    harness.detectChanges();
    expect(TestBed.inject(Router).url).toBe('/loja/importacoes?cursor=c2');
    await settle();
    expect(list().request.urlWithParams).toBe(`${LIST}?cursor=c2&limit=20`);
  });

  it('mostra o estado vazio com o modelo CSV quando a loja ainda não importou', async () => {
    await open();
    await answer(list(), { items: [] });

    expect(text()).toContain('Nenhuma planilha enviada ainda.');
    expect(page().querySelector('app-cursor-pagination')).toBeNull();
  });

  it('mostra o erro do histórico com Tentar de novo', async () => {
    await open();
    await answer(list(), null, { status: 503, statusText: 'Service Unavailable' });

    expect(text()).toContain('Não foi possível carregar o histórico.');
    button('Tentar de novo').click();
    await settle();
    await answer(list(), { items: [] });
    expect(text()).toContain('Nenhuma planilha enviada ainda.');
  });

  it('baixa o modelo CSV sem chamar a API', async () => {
    const { createObjectURL, revokeObjectURL } = URL;
    URL.createObjectURL = vi.fn(() => 'blob:modelo');
    URL.revokeObjectURL = vi.fn();
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    try {
      await open();
      await answer(list(), { items: [] });

      button('Baixar modelo CSV').click();
      expect(URL.createObjectURL).toHaveBeenCalledTimes(1);
      expect(click).toHaveBeenCalledTimes(1);
      http.verify();
    } finally {
      URL.createObjectURL = createObjectURL;
      URL.revokeObjectURL = revokeObjectURL;
      click.mockRestore();
    }
  });

  it('envia a planilha como text/csv, acompanha o lote do Location e recarrega o histórico no fim', async () => {
    await open();
    await answer(list(), { items: [] });
    const file = new File(['product_ref,category_code\r\n'], 'produtos.csv', { type: 'text/csv' });

    await choose(file);
    harness.detectChanges();
    expect(button('Enviando…')?.disabled).toBe(true);
    const upload = http.expectOne({ method: 'POST', url: LIST });
    expect(upload.request.body).toBe(file);
    expect(upload.request.headers.get('Content-Type')).toBe('text/csv');
    await answer(upload, importReport('PENDING', { id: 'b9', processed: 0 }), {
      status: 202,
      statusText: 'Accepted',
      headers: { Location: 'http://catalogo:8080/products/imports/b9' }
    });

    const toasts = TestBed.inject(ToastService).toasts();
    expect(toasts.at(-1)).toMatchObject({ kind: 'success', title: 'Planilha enviada' });
    expect(toasts.at(-1)?.message).toContain('produtos.csv');
    expect(button('Enviar planilha').disabled).toBe(false);
    await answer(list(), { items: [summary('b9', 'PENDING')] });
    const done = importReport('COMPLETED', { id: 'b9', total: 1, lines: [{ lineNumber: 2, sellerSku: 'CAM-P', outcome: 'CREATED', productId: 'p1' }] });
    await answer(http.expectOne(`${LIST}/b9?lines=false`), { ...done, lines: null });
    await answer(http.expectOne(`${LIST}/b9`), done);

    const title = page().querySelector('app-import-batch h2 a') as HTMLAnchorElement;
    expect(title.getAttribute('href')).toBe('/loja/importacoes/b9');
    expect(title.textContent).toContain('Lote de 08/11/2026 14:20');
    expect(text()).toContain('1 de 1 linha');
    await settle();
    await answer(list(), { items: [summary('b9', 'COMPLETED')] });
    http.verify();
  });

  it('mostra a recusa da planilha com as mensagens do servidor, sem os códigos', async () => {
    await open();
    await answer(list(), { items: [] });

    await choose(new File(['x'], 'errada.csv', { type: 'text/csv' }));
    await answer(
      http.expectOne({ method: 'POST', url: LIST }),
      {
        type: 'https://marketplace.dev/problems/invalid-import-file',
        title: 'Arquivo de importação inválido',
        status: 400,
        detail: 'O arquivo deve ser CSV com o cabeçalho exato',
        reasons: ['missing-column:on_hand', 'too-many-rows:5000'],
        reasonDetails: [
          { code: 'missing-column', params: ['on_hand'], message: 'Falta a coluna on_hand.' },
          { code: 'too-many-rows', params: ['5000'], message: 'O arquivo passa de 5000 linhas de dados.' }
        ]
      },
      { status: 400, statusText: 'Bad Request' }
    );

    const alert = page().querySelector('app-problem-alert') as HTMLElement;
    expect(alert.textContent).toContain('Não foi possível enviar a planilha');
    expect(Array.from(alert.querySelectorAll('li')).map(item => item.textContent?.trim())).toEqual([
      'Falta a coluna on_hand.',
      'O arquivo passa de 5000 linhas de dados.'
    ]);
    expect(alert.textContent).not.toContain('missing-column');
    expect(page().querySelector('app-import-batch')).toBeNull();
    expect(TestBed.inject(ToastService).toasts()).toHaveLength(0);
    http.verify();
  });
});
