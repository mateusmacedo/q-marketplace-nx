import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import {
  createEnvironmentInjector,
  EnvironmentInjector,
  runInInjectionContext,
  signal,
} from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  type ImportLineResponse,
  provideCatalogInventoryApi,
} from '@marketplace/catalog-inventory-admin-data-access';

import {
  type ImportTracking,
  POLL_INTERVAL_MS,
  retryAfterMs,
  trackImport,
} from './import-tracking';
import { API, importProgress, importReport, settle } from './testing';

const B1 = `${API}/products/imports/b1`;
const B1_PROGRESS = `${B1}?lines=false`;
const B2 = `${API}/products/imports/b2`;
const LINE: ImportLineResponse = {
  lineNumber: 2,
  sellerSku: 'CAM-P',
  outcome: 'CREATED',
  productId: 'p1',
};

describe('retryAfterMs', () => {
  it('lê o Retry-After em segundos ou como data HTTP e ignora o valor que não se lê', () => {
    const headers = (value: string | null) => ({ get: () => value });
    const now = Date.parse('2026-11-08T17:20:00Z');

    expect(retryAfterMs(headers('5'))).toBe(5000);
    expect(retryAfterMs({ headers: headers('1') })).toBe(1000);
    expect(retryAfterMs(headers('Sun, 08 Nov 2026 17:20:03 GMT'), now)).toBe(3000);
    expect(retryAfterMs(headers('Sun, 08 Nov 2026 17:19:00 GMT'), now)).toBe(0);
    expect(retryAfterMs(headers('logo'))).toBeUndefined();
    expect(retryAfterMs(headers(null))).toBeUndefined();
    expect(retryAfterMs(undefined)).toBeUndefined();
  });
});

describe('acompanhamento do lote', () => {
  let http: HttpTestingController;
  let injector: EnvironmentInjector;
  let id: ReturnType<typeof signal<string | undefined>>;
  let tracking: ImportTracking;
  let destroyed: boolean;
  let hidden: boolean;

  beforeEach(async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    hidden = false;
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideCatalogInventoryApi()],
    });
    http = TestBed.inject(HttpTestingController);
    injector = createEnvironmentInjector([], TestBed.inject(EnvironmentInjector));
    destroyed = false;
    id = signal<string | undefined>('b1');
    tracking = runInInjectionContext(injector, () => trackImport(id));
    TestBed.tick();
    await settle();
  });

  afterEach(() => {
    destroy();
    vi.useRealTimers();
    Reflect.deleteProperty(document, 'hidden');
  });

  function visibility(isHidden: boolean): void {
    hidden = isHidden;
    document.dispatchEvent(new Event('visibilitychange'));
  }

  function destroy(): void {
    if (!destroyed) {
      destroyed = true;
      injector.destroy();
    }
  }

  async function advance(ms: number): Promise<void> {
    await vi.advanceTimersByTimeAsync(ms);
    await settle();
  }

  it('consulta o andamento sem as linhas a cada 2 segundos e, no status terminal, lê o lote uma vez com as linhas', async () => {
    expect(tracking.loading()).toBe(true);
    http.expectOne(B1_PROGRESS).flush(importProgress('PENDING', { processed: 0 }));
    await settle();
    expect(tracking.report()?.status).toBe('PENDING');
    expect(tracking.report()?.lines).toBeNull();
    expect(tracking.running()).toBe(true);
    expect(tracking.loading()).toBe(false);

    await advance(POLL_INTERVAL_MS - 1);
    http.expectNone(B1_PROGRESS);
    await advance(1);
    http.expectOne(B1_PROGRESS).flush(importProgress('PROCESSING', { processed: 40 }));
    await settle();
    expect(tracking.report()?.processedLines).toBe(40);

    await advance(POLL_INTERVAL_MS);
    http.expectOne(B1_PROGRESS).flush(importProgress('COMPLETED', { processed: 100 }));
    await settle();
    expect(tracking.running()).toBe(true);
    expect(tracking.loading()).toBe(true);
    http.expectOne(B1).flush(importReport('COMPLETED', { lines: [LINE] }));
    await settle();
    expect(tracking.running()).toBe(false);
    expect(tracking.loading()).toBe(false);
    expect(tracking.report()?.lines).toEqual([LINE]);

    await advance(POLL_INTERVAL_MS * 5);
    http.verify();
  });

  it('lê o lote já terminado com as linhas uma vez só, sem repetir a consulta', async () => {
    http.expectOne(B1_PROGRESS).flush(importProgress('COMPLETED', { lines: [LINE] }));
    await settle();
    expect(tracking.report()).toBeUndefined();
    http.expectOne(B1).flush(importReport('COMPLETED', { lines: [LINE] }));
    await settle();
    expect(tracking.report()?.lines).toEqual([LINE]);

    await advance(POLL_INTERVAL_MS * 3);
    http.verify();
  });

  it('segue o Retry-After da resposta quando o servidor manda', async () => {
    http
      .expectOne(B1_PROGRESS)
      .flush(importProgress('PROCESSING'), { headers: { 'Retry-After': '5' } });
    await settle();

    await advance(POLL_INTERVAL_MS);
    http.expectNone(B1_PROGRESS);
    await advance(5000 - POLL_INTERVAL_MS);
    http.expectOne(B1_PROGRESS).flush(importProgress('COMPLETED'));
    await settle();
    http.expectOne(B1).flush(importReport('COMPLETED'));
    await settle();
    expect(tracking.running()).toBe(false);
    http.verify();
  });

  it('pausa com a aba oculta e retoma ao voltar, sem duplicar a consulta', async () => {
    http.expectOne(B1_PROGRESS).flush(importProgress('PROCESSING', { processed: 10 }));
    await settle();

    visibility(true);
    await advance(POLL_INTERVAL_MS * 5);
    http.expectNone(B1_PROGRESS);

    visibility(false);
    await settle();
    visibility(false);
    await settle();
    http.expectOne(B1_PROGRESS).flush(importProgress('PROCESSING', { processed: 20 }));
    await settle();
    expect(tracking.report()?.processedLines).toBe(20);

    visibility(false);
    await advance(POLL_INTERVAL_MS - 1);
    http.expectNone(B1_PROGRESS);
    await advance(1);
    http.expectOne(B1_PROGRESS).flush(importProgress('PROCESSING', { processed: 30 }));
    await settle();
    expect(tracking.report()?.processedLines).toBe(30);
    http.verify();
  });

  it('para de consultar quando quem acompanha é destruído, como ao sair da tela', async () => {
    http.expectOne(B1_PROGRESS).flush(importProgress('PROCESSING'));
    await settle();

    destroy();
    await advance(POLL_INTERVAL_MS * 3);
    visibility(false);
    await settle();
    expect(http.match(() => true)).toHaveLength(0);
    http.verify();
  });

  it('guarda a última leitura e o problema quando a consulta falha, e retoma em retry', async () => {
    http.expectOne(B1_PROGRESS).flush(importProgress('PROCESSING', { processed: 10 }));
    await settle();
    await advance(POLL_INTERVAL_MS);
    http.expectOne(B1_PROGRESS).flush(null, { status: 0, statusText: 'Unknown Error' });
    await settle();

    expect(tracking.report()?.processedLines).toBe(10);
    expect(tracking.problem()?.status).toBe(0);
    await advance(POLL_INTERVAL_MS * 3);
    http.expectNone(B1_PROGRESS);

    tracking.retry();
    await settle();
    http.expectOne(B1_PROGRESS).flush(importProgress('COMPLETED', { processed: 100 }));
    await settle();
    http.expectOne(B1).flush(importReport('COMPLETED', { processed: 100 }));
    await settle();
    expect(tracking.problem()).toBeNull();
    expect(tracking.report()?.processedLines).toBe(100);
  });

  it('mostra o problema do servidor quando o lote não existe', async () => {
    http.expectOne(B1_PROGRESS).flush(
      {
        type: 'https://marketplace.dev/problems/not-found',
        title: 'Não encontrado',
        status: 404,
        detail: 'Importação não encontrada',
      },
      { status: 404, statusText: 'Not Found' },
    );
    await settle();

    expect(tracking.report()).toBeUndefined();
    expect(tracking.problem()).toMatchObject({ status: 404, detail: 'Importação não encontrada' });
    await advance(POLL_INTERVAL_MS * 2);
    http.verify();
  });

  it('troca de lote: esquece o anterior, cancela a próxima consulta dele e lê o novo', async () => {
    http.expectOne(B1_PROGRESS).flush(importProgress('PROCESSING'));
    await settle();

    id.set('b2');
    TestBed.tick();
    expect(tracking.report()).toBeUndefined();
    await settle();
    http.expectOne(`${B2}?lines=false`).flush(importProgress('COMPLETED', { id: 'b2' }));
    await settle();
    http.expectOne(B2).flush(importReport('COMPLETED', { id: 'b2' }));
    await settle();
    expect(tracking.report()?.id).toBe('b2');

    await advance(POLL_INTERVAL_MS * 2);
    http.verify();
  });
});
