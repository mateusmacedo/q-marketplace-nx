import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter, Router, RouterOutlet } from '@angular/router';
import {
  type CategoryResponse,
  type ModerationReasonResponse,
  type ProductResponse,
  provideCatalogInventoryApi,
  type StatusChangeResponse,
} from '@marketplace/catalog-inventory-admin-data-access';
import {
  AdminSession,
  BATCH_RUNNING_WARNING,
  NavBadgeService,
  type SessionGrants,
  ToastService,
} from '@marketplace/shared-admin-ui';

import { routes } from './lib.routes';
import { ModerationQueuePageComponent } from './moderation-queue-page.component';
import { MODERATION_QUEUE_BADGE } from './queue-badge';

const API = '/api/catalog-inventory';
const QUEUE = `${API}/moderation/products?limit=20`;
const STATS = `${API}/stats/moderation/queue`;
const BATCH = `${API}/moderation/products/batch`;
const STORE = '3f2a91c0-0000-4000-8000-000000000001';

const P1: ProductResponse = {
  id: 'p1',
  storeId: STORE,
  categoryId: 'c1',
  currency: 'BRL',
  title: 'Camiseta Basic',
  description: 'Camiseta de algodão.',
  status: 'PENDING_MODERATION',
  version: 5,
  moderationQueuedAt: '2026-11-08T13:10:00Z',
  attributes: { material: 'Algodão' },
  skus: [
    {
      id: 's1',
      sellerSku: 'CAM-P',
      variantAttributes: { tamanho: 'P', cor: 'Azul' },
      offer: {
        currency: 'BRL',
        salePrice: { amount: 49.9, currency: 'BRL' },
        effectivePrice: { amount: 49.9, currency: 'BRL' },
        onPromotion: false,
      },
      stock: { available: 12, onHand: 12 },
    },
    {
      id: 's2',
      sellerSku: 'CAM-M',
      variantAttributes: { tamanho: 'M', cor: 'Azul' },
      stock: { available: 0, onHand: 0 },
    },
  ],
  actions: ['APPROVE', 'REJECT', 'BLOCK'],
};

const P2: ProductResponse = {
  id: 'p2',
  storeId: STORE,
  categoryId: 'c1',
  currency: 'BRL',
  title: 'Tênis Run',
  status: 'PENDING_MODERATION',
  version: 2,
  moderationQueuedAt: '2026-11-08T14:00:00Z',
  skus: [],
  actions: ['REJECT', 'BLOCK'],
  blockedActions: [
    {
      action: 'APPROVE',
      reason: { code: 'seller-not-eligible', message: 'A loja ainda não foi aprovada no KYB/KYC.' },
    },
  ],
};

const CATEGORY: CategoryResponse = {
  id: 'c1',
  name: 'Camisas',
  ancestors: [{ id: 'c0', name: 'Vestuário' }],
  effectiveAttributes: [
    { code: 'material', label: 'Material', type: 'OPTION' },
    { code: 'tamanho', label: 'Tamanho', type: 'OPTION', variant: true },
    { code: 'cor', label: 'Cor', type: 'OPTION', variant: true },
  ],
};

const HISTORY: StatusChangeResponse[] = [
  { transition: 'CREATE', at: '2026-11-08T12:00:00Z', actorSide: 'STORE', byCaller: false },
  { transition: 'SUBMIT', at: '2026-11-08T13:10:00Z', actorSide: 'STORE', byCaller: false },
];

const REASONS: ModerationReasonResponse[] = [
  {
    code: 'categoria-incorreta',
    label: 'Categoria incorreta',
    decisions: ['REJECT'],
    position: 10,
    active: true,
  },
];

class TestSession extends AdminSession {
  readonly grants = signal<SessionGrants>({
    capabilities: ['MODERATE_PRODUCTS', 'READ_SELLER_ELIGIBILITY'],
    permissions: { readTenantCatalog: true },
  });
  readonly storeId = signal<string | undefined>(undefined);
  readonly tenantId = signal<string | undefined>('tenant-1');
}

@Component({ imports: [RouterOutlet], template: '<router-outlet />' })
class Shell {}

describe('fila de moderação', () => {
  let fixture: ComponentFixture<Shell>;
  let http: HttpTestingController;
  let router: Router;
  let toast: ToastService;
  let session: TestSession;

  const element = () => fixture.nativeElement as HTMLElement;
  const buttons = (root: ParentNode = element()) =>
    Array.from(root.querySelectorAll('button')) as HTMLButtonElement[];
  const button = (text: string, root: ParentNode = element()) =>
    buttons(root).find(
      (item) => item.textContent?.replace(/\s+/g, ' ').trim() === text,
    ) as HTMLButtonElement;
  const queueItems = () =>
    Array.from(element().querySelectorAll('[cListGroupItem][role=listitem]')) as HTMLElement[];
  const detail = () => element().querySelector('app-moderation-item-page') as HTMLElement;
  const lastToast = () => toast.toasts().at(-1)?.message;

  /**
   * Espera a cadeia de promessas das chamadas e roda a detecção de mudanças, sem esperar a aplicação ficar estável,
   * porque uma chamada pendente só termina com o `flush` do teste.
   */
  async function settle(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    TestBed.tick();
  }

  async function openQueue(url = '/', items: ProductResponse[] = [P1, P2]): Promise<void> {
    await router.navigateByUrl(url);
    await settle();
    http.expectOne(STATS).flush({ count: 7 });
    http.expectOne(QUEUE).flush({ items, nextCursor: null });
    await settle();
  }

  async function flushDetail(product: ProductResponse = P1, etag = '"5"'): Promise<void> {
    http.expectOne(`${API}/products/${product.id}`).flush(product, { headers: { ETag: etag } });
    http.expectOne(`${API}/products/${product.id}/history`).flush(HISTORY);
    await settle();
    http.expectOne(`${API}/categories/c1`).flush(CATEGORY);
    http.expectOne(`${API}/stores/${STORE}/eligibility`).flush({
      storeId: STORE,
      status: 'APPROVED',
      canPublish: true,
      visibleInStorefront: true,
    });
    await settle();
  }

  async function reloadQueue(items: ProductResponse[]): Promise<void> {
    await settle();
    http.expectOne(QUEUE).flush({ items, nextCursor: null });
    await settle();
    http.expectOne(STATS).flush({ count: items.length });
    await settle();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideRouter(routes),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideCatalogInventoryApi(),
        { provide: AdminSession, useClass: TestSession },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    toast = TestBed.inject(ToastService);
    session = TestBed.inject(AdminSession) as TestSession;
    fixture = TestBed.createComponent(Shell);
  });

  afterEach(() => http.verify());

  describe('lista', () => {
    it('mostra a fila na ordem do servidor, com a loja e o tempo na fila, e alimenta o badge do menu', async () => {
      await openQueue();

      expect(queueItems().map((item) => item.querySelector('a')?.textContent?.trim())).toEqual([
        'Camiseta Basic',
        'Tênis Run',
      ]);
      expect(queueItems()[0].textContent).toContain('Loja 3f2a91c0…');
      expect(TestBed.inject(NavBadgeService).counts()[MODERATION_QUEUE_BADGE]).toBe(7);
      expect(element().textContent).toContain('Escolha um produto da fila para ver o detalhe.');
    });

    it('reaproveita o badge lido há menos de 60 s ao abrir a fila de novo', async () => {
      await openQueue();
      fixture.destroy();

      fixture = TestBed.createComponent(Shell);
      await settle();
      http.expectNone(STATS);
      http.expectOne(QUEUE).flush({ items: [P1], nextCursor: null });
      await settle();

      expect(queueItems()).toHaveLength(1);
      expect(TestBed.inject(NavBadgeService).counts()[MODERATION_QUEUE_BADGE]).toBe(7);
    });

    it('avisa quando a fila está vazia', async () => {
      await openQueue('/', []);
      expect(element().textContent).toContain('Nenhum produto aguardando moderação.');
    });

    it('mostra o erro da fila com Tentar de novo', async () => {
      await router.navigateByUrl('/');
      await settle();
      http.expectOne(STATS).flush({ count: 0 });
      http
        .expectOne(QUEUE)
        .flush(
          { title: 'Erro interno', status: 500, detail: 'Tente de novo em instantes.' },
          { status: 500, statusText: 'Error' },
        );
      await settle();

      expect(element().textContent).toContain('Não foi possível carregar a fila.');
      expect(element().textContent).toContain('Tente de novo em instantes.');
      button('Tentar de novo').click();
      await settle();
      http.expectOne(QUEUE).flush({ items: [P1], nextCursor: null });
      await settle();

      expect(queueItems().length).toBe(1);
    });

    it('aprova em lote depois da confirmação e mostra a recusa de cada produto com a mensagem do servidor', async () => {
      await openQueue();
      (element().querySelector('#moderation-queue-select-page') as HTMLInputElement).click();
      await settle();

      expect(button('Aprovar (1)').disabled).toBe(false);
      expect(button('Rejeitar (2)')).toBeTruthy();
      button('Aprovar (1)').click();
      await settle();

      const modal = element().querySelector('app-confirm-modal') as HTMLElement;
      expect(modal.textContent).toContain('Aprovar 1 produto?');
      button('Aprovar', modal.querySelector('c-modal-footer') as ParentNode).click();
      await settle();

      const request = http.expectOne(BATCH);
      expect(request.request.body).toEqual({
        action: 'APPROVE',
        items: [{ id: 'p1', version: 5 }],
      });
      request.flush({
        results: [
          {
            id: 'p1',
            outcome: 'FAILED',
            problem: {
              type: 'https://marketplace.dev/problems/product-not-publishable',
              title: 'Produto com pendências',
              status: 422,
              reasonDetails: [{ code: 'missing-offer', message: 'O SKU CAM-M não tem oferta.' }],
            },
          },
        ],
      });
      await reloadQueue([P1, P2]);

      expect(lastToast()).toBe('1 com falha, 1 ignorado');
      expect(element().querySelector('app-batch-result-alert')?.textContent).toContain(
        'Camiseta Basic: O SKU CAM-M não tem oferta.',
      );
      expect(element().textContent).not.toContain('missing-offer');
      expect(element().textContent).toContain('2 selecionados');
    });

    it('rejeita em lote com o mesmo motivo para todos', async () => {
      await openQueue();
      (element().querySelector('#moderation-queue-select-page') as HTMLInputElement).click();
      await settle();
      button('Rejeitar (2)').click();
      await settle();
      http.expectOne(`${API}/moderation/reasons?decision=REJECT`).flush(REASONS);
      await settle();

      const modal = element().querySelector('app-moderation-decision-modal') as HTMLElement;
      expect(modal.querySelector('[cModalTitle]')?.textContent?.trim()).toBe('Rejeitar 2 produtos');
      (modal.querySelector('input[type=checkbox]') as HTMLInputElement).click();
      await settle();
      button('Rejeitar', modal.querySelector('c-modal-footer') as ParentNode).click();
      await settle();

      const page = fixture.debugElement.query(By.directive(ModerationQueuePageComponent))
        .componentInstance as ModerationQueuePageComponent;
      expect(page.leaveWarning()).toBe(BATCH_RUNNING_WARNING);
      const request = http.expectOne(BATCH);
      expect(request.request.body).toEqual({
        action: 'REJECT',
        items: [
          { id: 'p1', version: 5 },
          { id: 'p2', version: 2 },
        ],
        reasonCodes: ['categoria-incorreta'],
        note: null,
      });
      request.flush({
        results: [
          { id: 'p1', outcome: 'SUCCEEDED', status: 'DRAFT', version: 6 },
          { id: 'p2', outcome: 'SUCCEEDED', status: 'DRAFT', version: 3 },
        ],
      });
      await reloadQueue([]);

      expect(lastToast()).toBe('2 rejeitados');
      expect(page.leaveWarning()).toBeNull();
      expect(element().textContent).not.toContain('selecionados');
      expect(element().textContent).toContain('Nenhum produto aguardando moderação.');
    });

    it('não mostra o resumo nem recarrega a fila quando a pessoa sai da tela no meio do lote', async () => {
      await openQueue();
      (element().querySelector('#moderation-queue-select-page') as HTMLInputElement).click();
      await settle();
      button('Aprovar (1)').click();
      await settle();
      const modal = element().querySelector('app-confirm-modal') as HTMLElement;
      button('Aprovar', modal.querySelector('c-modal-footer') as ParentNode).click();
      await settle();
      const request = http.expectOne(BATCH);

      fixture.destroy();
      request.flush({
        results: [{ id: 'p1', outcome: 'SUCCEEDED', status: 'ACTIVE', version: 6 }],
      });
      await new Promise((resolve) => setTimeout(resolve));

      expect(lastToast()).toBeUndefined();
      http.expectNone(QUEUE);
      http.expectNone(STATS);
    });

    it('mantém o modal do lote aberto com a recusa quando nada foi decidido', async () => {
      await openQueue();
      (element().querySelector('#moderation-queue-select-page') as HTMLInputElement).click();
      await settle();
      button('Bloquear (2)').click();
      await settle();
      http
        .expectOne(`${API}/moderation/reasons?decision=BLOCK`)
        .flush(REASONS.map((reason) => ({ ...reason, decisions: ['BLOCK'] })));
      await settle();
      const modal = element().querySelector('app-moderation-decision-modal') as HTMLElement;
      (modal.querySelector('input[type=checkbox]') as HTMLInputElement).click();
      await settle();
      button('Bloquear', modal.querySelector('c-modal-footer') as ParentNode).click();
      await settle();

      http.expectOne(BATCH).flush(
        {
          type: 'https://marketplace.dev/problems/invalid-moderation-reason',
          title: 'Motivo inválido',
          status: 422,
          reasonDetails: [
            { code: 'reason-inactive', message: 'O motivo Categoria incorreta foi desativado.' },
          ],
        },
        { status: 422, statusText: 'Unprocessable Entity' },
      );
      await settle();
      http.expectOne(`${API}/moderation/reasons?decision=BLOCK`).flush([]);
      await settle();

      expect(modal.querySelector('[cModalTitle]')?.textContent?.trim()).toBe('Bloquear 2 produtos');
      expect(modal.textContent).toContain('O motivo Categoria incorreta foi desativado.');
      expect(toast.toasts()).toEqual([]);
    });
  });

  describe('detalhe', () => {
    it('mostra o produto com a categoria, os atributos, os SKUs com preço e saldo, a loja e o histórico', async () => {
      await openQueue('/p1');
      await flushDetail();

      const page = detail();
      expect(page.querySelector('h1')?.textContent?.trim()).toBe('Camiseta Basic');
      expect(page.textContent).toContain('Vestuário › Camisas');
      expect(page.textContent).toContain('Aprovada');
      expect(page.textContent).toContain('Material');
      expect(page.textContent).toContain('Algodão');
      const skuRows = Array.from(page.querySelectorAll('tbody tr')) as HTMLTableRowElement[];
      expect(skuRows[0].cells[0].textContent?.trim()).toBe('CAM-P');
      expect(skuRows[0].cells[1].textContent?.trim()).toBe('P / Azul');
      expect(skuRows[0].cells[2].textContent).toContain('49,90');
      expect(skuRows[0].cells[3].textContent).toContain('12');
      expect(skuRows[1].cells[2].textContent).toContain('Sem oferta');
      expect(page.textContent).toContain('Enviado para moderação');
      expect(queueItems()[0].classList).toContain('active');
    });

    it('mostra Aprovar desabilitado com a mensagem do servidor quando a ação vem barrada', async () => {
      await openQueue('/p2');
      await flushDetail(P2, '"2"');

      const approve = button('Aprovar', detail());
      expect(approve.disabled).toBe(true);
      expect(detail().textContent).toContain('A loja ainda não foi aprovada no KYB/KYC.');
      expect(detail().textContent).not.toContain('seller-not-eligible');
      expect(button('Rejeitar', detail()).disabled).toBe(false);
    });

    it('aprova sem confirmação, com If-Match, avisa e abre o próximo item da fila', async () => {
      await openQueue('/p1');
      await flushDetail();

      button('Aprovar', detail()).click();
      await settle();
      const request = http.expectOne(`${API}/moderation/products/p1/approve`);
      expect(request.request.method).toBe('POST');
      expect(request.request.headers.get('If-Match')).toBe('"5"');
      request.flush({ ...P1, status: 'ACTIVE', version: 6 });
      await reloadQueue([P2]);
      await settle();

      expect(lastToast()).toBe('Produto aprovado.');
      expect(router.url).toBe('/p2');
      await flushDetail(P2, '"2"');
      expect(detail().querySelector('h1')?.textContent?.trim()).toBe('Tênis Run');
    });

    it('no conflito ao aprovar, mostra o aviso e carrega a versão atual', async () => {
      await openQueue('/p1');
      await flushDetail();

      button('Aprovar', detail()).click();
      await settle();
      http.expectOne(`${API}/moderation/products/p1/approve`).flush(
        {
          type: 'https://marketplace.dev/problems/version-mismatch',
          title: 'Versão desatualizada',
          status: 412,
        },
        { status: 412, statusText: 'Precondition Failed' },
      );
      await settle();

      expect(detail().textContent).toContain('Outra pessoa alterou este registro');
      button('Carregar versão atual', detail()).click();
      await settle();
      expect(detail().textContent).toContain('Outra pessoa alterou este registro');
      expect(button('Aprovar', detail()).disabled).toBe(true);
      button('Aprovar', detail()).click();
      await settle();
      http.expectNone(`${API}/moderation/products/p1/approve`);
      http
        .expectOne(`${API}/products/p1`)
        .flush({ ...P1, version: 7 }, { headers: { ETag: '"7"' } });
      await settle();

      expect(detail().textContent).not.toContain('Outra pessoa alterou este registro');
      button('Aprovar', detail()).click();
      await settle();
      const retry = http.expectOne(`${API}/moderation/products/p1/approve`);
      expect(retry.request.headers.get('If-Match')).toBe('"7"');
      retry.flush({ ...P1, status: 'ACTIVE', version: 8 });
      await reloadQueue([]);
      await settle();

      expect(router.url).toBe('/');
    });

    it('não leva a recusa da aprovação de um produto para o detalhe de outro', async () => {
      await openQueue('/p1');
      await flushDetail();

      button('Aprovar', detail()).click();
      await settle();
      const approve = http.expectOne(`${API}/moderation/products/p1/approve`);
      await router.navigateByUrl('/p2');
      await settle();
      await flushDetail(P2, '"2"');
      approve.flush(
        {
          type: 'https://marketplace.dev/problems/product-not-publishable',
          title: 'Produto com pendências',
          status: 422,
          reasonDetails: [{ code: 'missing-offer', message: 'O SKU CAM-M não tem oferta.' }],
        },
        { status: 422, statusText: 'Unprocessable Entity' },
      );
      await settle();

      expect(detail().querySelector('h1')?.textContent?.trim()).toBe('Tênis Run');
      expect(detail().textContent).not.toContain('Não foi possível aprovar');
      expect(detail().textContent).not.toContain('O SKU CAM-M não tem oferta.');
    });

    it('tira da seleção do lote o produto decidido pelo detalhe', async () => {
      await openQueue();
      (element().querySelector('#moderation-queue-select-page') as HTMLInputElement).click();
      await settle();
      expect(element().textContent).toContain('2 selecionados');

      await router.navigateByUrl('/p1');
      await settle();
      await flushDetail();
      button('Aprovar', detail()).click();
      await settle();
      http
        .expectOne(`${API}/moderation/products/p1/approve`)
        .flush({ ...P1, status: 'ACTIVE', version: 6 });
      await reloadQueue([P2]);
      await settle();
      await flushDetail(P2, '"2"');

      expect(element().textContent).toContain('1 selecionado');
      expect(button('Aprovar (0)')?.disabled).toBe(true);
      expect(button('Rejeitar (1)')).toBeTruthy();
    });

    it('rejeita pelo modal com os motivos e o complemento, e a recusa fica no modal', async () => {
      await openQueue('/p1');
      await flushDetail();

      button('Rejeitar', detail()).click();
      await settle();
      http.expectOne(`${API}/moderation/reasons?decision=REJECT`).flush(REASONS);
      await settle();
      const modal = detail().querySelector('app-moderation-decision-modal') as HTMLElement;
      expect(modal.querySelector('[cModalTitle]')?.textContent?.trim()).toBe(
        'Rejeitar "Camiseta Basic"',
      );
      (modal.querySelector('input[type=checkbox]') as HTMLInputElement).click();
      const note = modal.querySelector('textarea') as HTMLTextAreaElement;
      note.value = 'A gola não bate com a foto.';
      note.dispatchEvent(new Event('input'));
      await settle();
      button('Rejeitar', modal.querySelector('c-modal-footer') as ParentNode).click();
      await settle();

      const refused = http.expectOne(`${API}/moderation/products/p1/reject`);
      expect(refused.request.headers.get('If-Match')).toBe('"5"');
      expect(refused.request.body).toEqual({
        reasonCodes: ['categoria-incorreta'],
        note: 'A gola não bate com a foto.',
      });
      refused.flush(
        {
          type: 'https://marketplace.dev/problems/invalid-moderation-reason',
          title: 'Motivo inválido',
          status: 422,
          reasonDetails: [
            {
              code: 'reason-not-applicable',
              message: 'O motivo Categoria incorreta não vale para rejeitar.',
            },
          ],
        },
        { status: 422, statusText: 'Unprocessable Entity' },
      );
      await settle();
      http.expectOne(`${API}/moderation/reasons?decision=REJECT`).flush([]);
      await settle();

      expect(modal.textContent).toContain('O motivo Categoria incorreta não vale para rejeitar.');
      expect(note.value).toBe('A gola não bate com a foto.');

      button('Rejeitar', modal.querySelector('c-modal-footer') as ParentNode).click();
      await settle();
      const accepted = http.expectOne(`${API}/moderation/products/p1/reject`);
      expect(accepted.request.body).toEqual({
        reasonCodes: [],
        note: 'A gola não bate com a foto.',
      });
      accepted.flush({ ...P1, status: 'DRAFT', version: 6 });
      await reloadQueue([P2]);
      await settle();

      expect(lastToast()).toBe('Produto rejeitado. Ele voltou ao rascunho do lojista.');
      expect(router.url).toBe('/p2');
      await flushDetail(P2, '"2"');
    });

    it('não lê a situação da loja sem a capacidade que a libera', async () => {
      session.grants.set({ capabilities: ['MODERATE_PRODUCTS'], permissions: {} });
      await openQueue('/p1');
      http.expectOne(`${API}/products/p1`).flush(P1, { headers: { ETag: '"5"' } });
      http.expectOne(`${API}/products/p1/history`).flush(HISTORY);
      await settle();
      http.expectOne(`${API}/categories/c1`).flush(CATEGORY);
      await settle();

      http.expectNone(`${API}/stores/${STORE}/eligibility`);
      expect(detail().textContent).not.toContain('Loja: ');
    });
  });
});
