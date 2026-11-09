import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Injector, runInInjectionContext, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  type ProductCheckRequest,
  type ProductResponse,
  provideCatalogInventoryApi
} from '@marketplace/catalog-inventory-admin-data-access';

import { type ProductDraftCheck, productDraftCheck } from './product-draft-check';

const CHECK = '/api/catalog-inventory/products/p1/check';
const DELAY = 20;
const SAVED = [{ code: 'no-sku', params: [], message: 'Adicione ao menos um SKU.', field: 'skus' }];
const DRAFT_ISSUES = [{ code: 'missing-offer', params: ['CAM-P'], message: 'Defina o preço do SKU CAM-P.', field: 'skus[CAM-P].offer' }];
const CHANGE = {
  removedAttributes: [{ code: 'gola', label: 'Gola', value: 'V', reason: 'A categoria nova não tem Gola.' }],
  newRequired: [{ code: 'tecido', label: 'Tecido', variant: false }],
  blockingSkus: []
};

describe('conferência do rascunho no servidor', () => {
  let http: HttpTestingController;
  let product: ReturnType<typeof signal<Pick<ProductResponse, 'id' | 'publishingIssues'>>>;
  let draft: ReturnType<typeof signal<ProductCheckRequest | undefined>>;
  let check: ProductDraftCheck;

  const settle = async () => {
    await new Promise(resolve => setTimeout(resolve, DELAY * 2));
    TestBed.tick();
  };
  const answer = async (body: object) => {
    const request = await vi.waitFor(() => http.expectOne(CHECK));
    request.flush(body);
    await vi.waitFor(() => {
      TestBed.tick();
      expect(check.loading()).toBe(false);
    });
    return request.request.body;
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideCatalogInventoryApi()]
    });
    http = TestBed.inject(HttpTestingController);
    product = signal({ id: 'p1', publishingIssues: SAVED });
    draft = signal<ProductCheckRequest | undefined>(undefined);
    check = runInInjectionContext(TestBed.inject(Injector), () => productDraftCheck(product, draft, DELAY));
    TestBed.tick();
  });

  afterEach(() => http.verify());

  it('usa as pendências do produto salvo sem chamar o servidor enquanto não há rascunho', async () => {
    await settle();
    expect(check.issues()).toEqual(SAVED);
    expect(check.categoryChange()).toBeNull();
    expect(check.loading()).toBe(false);
    http.expectNone(CHECK);
  });

  it('espera o fim da digitação e confere só o último rascunho', async () => {
    draft.set({ categoryId: 'c1', attributes: { cor: 'az' } });
    TestBed.tick();
    expect(check.loading()).toBe(true);
    draft.set({ categoryId: 'c1', attributes: { cor: 'azul' } });
    TestBed.tick();

    await settle();
    expect(await answer({ publishingIssues: DRAFT_ISSUES, categoryChange: null })).toEqual({
      categoryId: 'c1',
      attributes: { cor: 'azul' }
    });
    expect(check.issues()).toEqual(DRAFT_ISSUES);
    expect(check.categoryChange()).toBeNull();
  });

  it('cancela a conferência em voo quando outro rascunho assenta, e só a resposta nova chega à tela', async () => {
    draft.set({ categoryId: 'c1', attributes: { cor: 'az' } });
    TestBed.tick();
    await settle();
    const first = await vi.waitFor(() => http.expectOne(CHECK));

    draft.set({ categoryId: 'c1', attributes: { cor: 'azul' } });
    TestBed.tick();
    await settle();
    expect(first.cancelled).toBe(true);
    expect(check.loading()).toBe(true);
    expect(check.issues()).toBeUndefined();

    expect(await answer({ publishingIssues: DRAFT_ISSUES, categoryChange: null })).toEqual({
      categoryId: 'c1',
      attributes: { cor: 'azul' }
    });
    expect(check.issues()).toEqual(DRAFT_ISSUES);
  });

  it('mantém a resposta anterior e o carregamento quando ela chega durante a espera do rascunho novo', async () => {
    draft.set({ categoryId: 'c1', attributes: { cor: 'az' } });
    TestBed.tick();
    await settle();
    const first = await vi.waitFor(() => http.expectOne(CHECK));

    draft.set({ categoryId: 'c2', attributes: { cor: 'azul' } });
    TestBed.tick();
    first.flush({ publishingIssues: SAVED, categoryChange: CHANGE });
    // WHY: a tela lê o sinal a cada detecção de mudanças; aqui a leitura precisa acontecer antes do fim da espera
    // de DELAY ms, senão ninguém observou a resposta antiga antes de o rascunho novo assentar.
    await vi.waitFor(
      () => {
        TestBed.tick();
        expect(check.issues()).toEqual(SAVED);
      },
      { interval: 1 }
    );
    expect(check.loading()).toBe(true);
    expect(check.categoryChange()).toEqual(CHANGE);

    await settle();
    await answer({ publishingIssues: DRAFT_ISSUES, categoryChange: null });
    expect(check.issues()).toEqual(DRAFT_ISSUES);
    expect(check.categoryChange()).toBeNull();
  });

  it('traz a prévia da troca de categoria e volta às pendências salvas sem rascunho', async () => {
    draft.set({ categoryId: 'c2', attributes: { gola: 'V' } });
    TestBed.tick();
    await settle();
    await answer({ publishingIssues: [], categoryChange: CHANGE });
    expect(check.categoryChange()).toEqual(CHANGE);
    expect(check.issues()).toEqual([]);

    draft.set(undefined);
    TestBed.tick();
    expect(check.issues()).toEqual(SAVED);
    expect(check.categoryChange()).toBeNull();
    expect(check.loading()).toBe(false);
  });

  it('mostra a falha da conferência como problema e confere de novo', async () => {
    draft.set({ categoryId: 'c1' });
    TestBed.tick();
    await settle();
    const failed = await vi.waitFor(() => http.expectOne(CHECK));
    failed.flush(
      { type: 'https://marketplace.dev/problems/not-found', title: 'Não encontrado', detail: 'Categoria não encontrada' },
      { status: 404, statusText: 'Not Found' }
    );
    await vi.waitFor(() => {
      TestBed.tick();
      expect(check.problem()).toEqual(expect.objectContaining({ status: 404, detail: 'Categoria não encontrada' }));
    });

    check.reload();
    TestBed.tick();
    await answer({ publishingIssues: DRAFT_ISSUES, categoryChange: null });
    expect(check.problem()).toBeUndefined();
    expect(check.issues()).toEqual(DRAFT_ISSUES);
  });
});
