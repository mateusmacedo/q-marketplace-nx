import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  type TestRequest,
} from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import {
  type OfferResponse,
  provideCatalogInventoryApi,
} from '@marketplace/catalog-inventory-admin-data-access';
import { ToastService } from '@marketplace/shared-admin-ui';
import type { ActionState } from '@marketplace/shared-web-util';

import { SkuOfferSectionComponent } from './sku-offer-section.component';

const API = '/api/catalog-inventory';
const OFFER_URL = `${API}/offers/s1`;

const OFFER: OfferResponse = {
  skuId: 's1',
  currency: 'BRL',
  price: {
    listPrice: { amount: 59.9, currency: 'BRL' },
    salePrice: { amount: 49.9, currency: 'BRL' },
  },
  effectivePrice: { salePrice: { amount: 39.9, currency: 'BRL' }, promotionId: 'pr-1' },
  promotions: [
    {
      id: 'pr-1',
      salePrice: { amount: 39.9, currency: 'BRL' },
      startsAt: '2026-11-01T13:00:00Z',
      endsAt: '2099-11-15T02:59:00Z',
      phase: 'ACTIVE',
    },
    {
      id: 'pr-old',
      salePrice: { amount: 29.9, currency: 'BRL' },
      startsAt: '2026-01-01T13:00:00Z',
      endsAt: '2026-01-05T13:00:00Z',
      phase: 'ENDED',
    },
  ],
  version: 2,
};

@Component({
  imports: [SkuOfferSectionComponent],
  template: `<app-sku-offer-section (changed)="changes = changes + 1" [editOffer]="editOffer()" currency="BRL" skuId="s1" />`,
})
class Host {
  readonly editOffer = signal<ActionState>({ allowed: true });
  changes = 0;
}

describe('SkuOfferSectionComponent', () => {
  let fixture: ComponentFixture<Host>;
  let http: HttpTestingController;

  const element = () => fixture.nativeElement as HTMLElement;
  const text = (root: Element = element()) => root.textContent?.replace(/\s+/g, ' ') ?? '';
  const render = () => TestBed.tick();
  const button = (root: ParentNode, label: string) =>
    (Array.from(root.querySelectorAll('button')) as HTMLButtonElement[]).find(
      (item) => item.textContent?.replace(/\s+/g, ' ').trim() === label,
    ) as HTMLButtonElement;
  const input = (label: string) => {
    const target = (Array.from(element().querySelectorAll('label')) as HTMLLabelElement[]).find(
      (item) => item.textContent?.trim() === label,
    );
    return element().querySelector(`[id="${target?.htmlFor}"]`) as HTMLInputElement;
  };
  const type = (target: HTMLInputElement, value: string) => {
    target.value = value;
    target.dispatchEvent(new Event('input'));
    render();
  };
  const offerRequest = (method = 'GET') =>
    vi.waitFor(() =>
      http.expectOne((request) => request.url === OFFER_URL && request.method === method),
    );
  const flushOffer = async (
    request: TestRequest,
    offer: OfferResponse,
    etag = `"${offer.version}"`,
  ) => {
    request.flush(offer, { headers: { ETag: etag } });
    await vi.waitFor(() => {
      render();
      expect(input('Por')).toBeTruthy();
    });
  };
  const submitPrice = () => {
    (input('Por').closest('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    render();
  };

  async function open(editOffer: ActionState = { allowed: true }) {
    TestBed.configureTestingModule({
      imports: [Host],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideCatalogInventoryApi()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    fixture.componentInstance.editOffer.set(editOffer);
    render();
  }

  afterEach(() => http.verify());

  it('mostra o preço "de" e "por", o vigente com a promoção e só as promoções não encerradas', async () => {
    await open();
    await flushOffer(await offerRequest(), OFFER);

    expect(input('De').valueAsNumber).toBe(59.9);
    expect(input('Por').valueAsNumber).toBe(49.9);
    expect(text()).toContain('Vigente agora: R$ 39,90 (promoção)');
    const items = Array.from(element().querySelectorAll('li'));
    expect(items).toHaveLength(1);
    expect(text(items[0])).toContain('01/11/2026 10:00 a');
    expect(text(items[0])).toContain('R$ 39,90');
    expect(text(items[0])).toContain('Vigente');
  });

  it('troca o preço com o If-Match da oferta e avisa a tabela', async () => {
    await open();
    await flushOffer(await offerRequest(), OFFER);
    const success = vi.spyOn(TestBed.inject(ToastService), 'success');

    type(input('Por'), '45.5');
    type(input('De'), '');
    submitPrice();
    const request = await offerRequest('PUT');
    expect(request.request.headers.get('If-Match')).toBe('"2"');
    expect(request.request.body).toEqual({
      salePrice: { amount: 45.5, currency: 'BRL' },
      listPrice: null,
    });
    request.flush(
      { ...OFFER, price: { salePrice: { amount: 45.5, currency: 'BRL' } }, version: 3 },
      { headers: { ETag: '"3"' } },
    );

    await vi.waitFor(() => expect(success).toHaveBeenCalledWith('Preço salvo.'));
    expect(fixture.componentInstance.changes).toBe(1);
  });

  it('cria a oferta na primeira gravação, sem If-Match, e lê a oferta criada', async () => {
    await open();
    (await offerRequest()).flush(
      { type: 'https://marketplace.dev/problems/not-found', title: 'Não encontrado', status: 404 },
      { status: 404, statusText: 'Not Found' },
    );
    await vi.waitFor(() => {
      render();
      expect(text()).toContain('O SKU ainda não tem preço.');
    });

    type(input('Por'), '49.9');
    submitPrice();
    const create = await offerRequest('PUT');
    expect(create.request.headers.has('If-Match')).toBe(false);
    create.flush(null, { status: 201, statusText: 'Created' });

    await flushOffer(await offerRequest(), {
      ...OFFER,
      promotions: [],
      effectivePrice: { salePrice: { amount: 49.9, currency: 'BRL' } },
      version: 1,
    });
    await vi.waitFor(() => {
      render();
      expect(text()).toContain('Vigente agora: R$ 49,90');
    });
  });

  it('mostra o conflito de edição e carrega a versão atual da oferta', async () => {
    await open();
    await flushOffer(await offerRequest(), OFFER);

    type(input('Por'), '10');
    submitPrice();
    (await offerRequest('PUT')).flush(
      {
        type: 'https://marketplace.dev/problems/version-mismatch',
        title: 'Versão divergente',
        status: 412,
      },
      { status: 412, statusText: 'Precondition Failed' },
    );
    await vi.waitFor(() => {
      render();
      expect(text()).toContain('Outra pessoa alterou este registro');
    });
    expect(input('Por').valueAsNumber).toBe(10);

    button(element(), 'Carregar versão atual').click();
    await flushOffer(await offerRequest(), { ...OFFER, version: 5 });
    await vi.waitFor(() => {
      render();
      expect(input('Por').valueAsNumber).toBe(49.9);
    });
    expect(text()).not.toContain('Outra pessoa alterou');
  });

  it('mostra a recusa de regra com a mensagem do servidor, sem o código', async () => {
    await open();
    await flushOffer(await offerRequest(), OFFER);

    type(input('De'), '10');
    submitPrice();
    (await offerRequest('PUT')).flush(
      {
        type: 'https://marketplace.dev/problems/list-price-below-sale-price',
        title: 'Preço inválido',
        status: 422,
        reasonDetails: [
          {
            code: 'list-price-below-sale-price',
            message: 'O preço "de" não pode ser menor que o "por".',
          },
        ],
      },
      { status: 422, statusText: 'Unprocessable Content' },
    );
    await vi.waitFor(() => {
      render();
      expect(text()).toContain('O preço "de" não pode ser menor que o "por".');
    });
    expect(text()).not.toContain('list-price-below-sale-price');
  });

  it('agenda a promoção lendo as datas no fuso de São Paulo e enviando em UTC', async () => {
    await open();
    await flushOffer(await offerRequest(), OFFER);

    button(element(), '+ Agendar').click();
    render();
    type(input('Início'), '2026-11-20T10:00');
    type(input('Fim'), '2026-11-25T23:59');
    type(input('Preço'), '35');
    (input('Início').closest('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    render();

    const request = await vi.waitFor(() => http.expectOne(`${OFFER_URL}/promotions`));
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('"2"');
    expect(request.request.body).toEqual({
      salePrice: { amount: 35, currency: 'BRL' },
      startsAt: '2026-11-20T13:00:00.000Z',
      endsAt: '2026-11-26T02:59:00.000Z',
    });
    request.flush(null, { status: 201, statusText: 'Created' });
    await flushOffer(await offerRequest(), { ...OFFER, version: 3 });
    await vi.waitFor(() => {
      render();
      expect(input('Início')).toBeNull();
    });
  });

  it('cancela a promoção só depois da confirmação, com o If-Match da oferta', async () => {
    await open();
    await flushOffer(await offerRequest(), OFFER);

    button(element().querySelector('li') as HTMLElement, 'Cancelar').click();
    render();
    const modal = element().querySelector('app-confirm-modal') as HTMLElement;
    expect(text(modal)).toContain('A promoção deixa de valer');

    button(modal.querySelector('c-modal-footer') as HTMLElement, 'Cancelar promoção').click();
    const request = await vi.waitFor(() => http.expectOne(`${OFFER_URL}/promotions/pr-1`));
    expect(request.request.method).toBe('DELETE');
    expect(request.request.headers.get('If-Match')).toBe('"2"');
    request.flush(
      {
        ...OFFER,
        promotions: [],
        effectivePrice: { salePrice: { amount: 49.9, currency: 'BRL' } },
        version: 3,
      },
      {
        headers: { ETag: '"3"' },
      },
    );
    await vi.waitFor(() => {
      render();
      expect(text()).toContain('Nenhuma promoção vigente ou agendada.');
    });
  });

  it('mantém o preço digitado e não salvo ao agendar e ao cancelar uma promoção', async () => {
    await open();
    await flushOffer(await offerRequest(), OFFER);

    type(input('Por'), '44.9');
    button(element(), '+ Agendar').click();
    render();
    type(input('Início'), '2026-11-20T10:00');
    type(input('Fim'), '2026-11-25T23:59');
    type(input('Preço'), '35');
    (input('Início').closest('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    render();
    (await vi.waitFor(() => http.expectOne(`${OFFER_URL}/promotions`))).flush(null, {
      status: 201,
      statusText: 'Created',
    });
    await flushOffer(await offerRequest(), { ...OFFER, version: 3 });
    expect(input('Por').valueAsNumber).toBe(44.9);

    button(element().querySelector('li') as HTMLElement, 'Cancelar').click();
    render();
    const modal = element().querySelector('app-confirm-modal') as HTMLElement;
    button(modal.querySelector('c-modal-footer') as HTMLElement, 'Cancelar promoção').click();
    (await vi.waitFor(() => http.expectOne(`${OFFER_URL}/promotions/pr-1`))).flush(
      { ...OFFER, promotions: [], version: 4 },
      { headers: { ETag: '"4"' } },
    );
    await vi.waitFor(() => {
      render();
      expect(text()).toContain('Nenhuma promoção vigente ou agendada.');
    });
    expect(input('Por').valueAsNumber).toBe(44.9);

    submitPrice();
    const save = await offerRequest('PUT');
    expect(save.request.headers.get('If-Match')).toBe('"4"');
    expect(save.request.body.salePrice).toEqual({ amount: 44.9, currency: 'BRL' });
    save.flush(
      {
        ...OFFER,
        promotions: [],
        price: { salePrice: { amount: 44.9, currency: 'BRL' } },
        version: 5,
      },
      {
        headers: { ETag: '"5"' },
      },
    );
    await vi.waitFor(() => {
      render();
      expect(fixture.componentInstance.changes).toBe(3);
    });
  });

  it('fica só leitura sem EDIT_OFFER, com o motivo do servidor', async () => {
    await open({ allowed: false, blockedReason: 'Produto arquivado.' });
    await flushOffer(await offerRequest(), OFFER);

    expect(input('Por').disabled).toBe(true);
    expect(button(element(), 'Salvar preço')).toBeUndefined();
    expect(button(element(), '+ Agendar')).toBeUndefined();
    expect(button(element().querySelector('li') as HTMLElement, 'Cancelar')).toBeUndefined();
    expect(text()).toContain('Preço e promoções só leitura. Produto arquivado.');
  });
});
