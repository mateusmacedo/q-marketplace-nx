import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { computed, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideCatalogInventoryApi } from '@marketplace/catalog-inventory-admin-data-access';
import { AdminSession, NO_GRANTS } from '@marketplace/shared-admin-ui';

import { StoreStatusPageComponent } from './store-status-page.component';

const STORE = '3f2a9c1e-0000-4000-8000-000000000001';
const ELIGIBILITY = `/api/catalog-inventory/stores/${STORE}/eligibility`;

class FakeSession extends AdminSession {
  readonly store = signal<string | undefined>(STORE);
  override readonly grants = signal(NO_GRANTS);
  override readonly storeId = computed(() => this.store());
  override readonly tenantId = signal('t1');
}

describe('situação da loja', () => {
  let http: HttpTestingController;
  let session: FakeSession;
  let fixture: ComponentFixture<StoreStatusPageComponent>;

  const text = (element: Element | null) => element?.textContent?.replace(/\s+/g, ' ').trim();
  const element = (selector: string) =>
    fixture.nativeElement.querySelector(selector) as HTMLElement | null;
  const render = () => {
    fixture = TestBed.createComponent(StoreStatusPageComponent);
    fixture.detectChanges();
  };
  const answer = async (body: object, status?: number) => {
    const request = await vi.waitFor(() => {
      TestBed.tick();
      return http.expectOne(ELIGIBILITY);
    });
    request.flush(body, status ? { status, statusText: 'Erro' } : undefined);
    await fixture.whenStable();
    fixture.detectChanges();
  };

  beforeEach(() => {
    session = new FakeSession();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideCatalogInventoryApi(),
        { provide: AdminSession, useValue: session },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('mostra o status no callout com a cor dele e o efeito que o servidor calculou', async () => {
    render();
    expect(element('app-loading-state')).not.toBeNull();

    await answer({
      storeId: STORE,
      status: 'APPROVED',
      canPublish: true,
      visibleInStorefront: true,
    });

    expect(text(element('h1'))).toBe('Situação da loja');
    expect(text(element('h1 + div'))).toBe('KYB/KYC da loja 3f2a9c1e');
    expect(element('c-callout')?.classList).toContain('callout-success');
    expect(text(element('c-callout c-badge'))).toBe('Aprovada');
    expect(Array.from(fixture.nativeElement.querySelectorAll('c-callout p'), text)).toEqual([
      'Produtos aprovados vão para a vitrine, e os pausados podem ser reativados.',
      'Os produtos ativos aparecem na vitrine.',
    ]);
  });

  it('tira o efeito dos campos calculados, mesmo que o status não combine com eles', async () => {
    render();
    await answer({
      storeId: STORE,
      status: 'APPROVED',
      canPublish: false,
      visibleInStorefront: false,
    });

    expect(Array.from(fixture.nativeElement.querySelectorAll('c-callout p'), text)).toEqual([
      'Você pode cadastrar produtos, mas eles só são aprovados e reativados depois da aprovação no KYB/KYC.',
      'Os produtos não aparecem na vitrine.',
    ]);
  });

  it('mostra o status que a tela não conhece como veio, em cinza', async () => {
    render();
    await answer({
      storeId: STORE,
      status: 'SUSPENDED',
      canPublish: false,
      visibleInStorefront: false,
    });

    expect(element('c-callout')?.classList).toContain('callout-secondary');
    expect(text(element('c-callout c-badge'))).toBe('SUSPENDED');
  });

  it('mostra o erro com o texto do servidor e tenta de novo', async () => {
    render();
    await answer(
      {
        type: 'https://marketplace.dev/problems/http-503',
        title: 'Indisponível',
        detail: 'Serviço fora do ar.',
      },
      503,
    );

    expect(text(element('app-error-state'))).toContain(
      'Não foi possível carregar a situação da loja.',
    );
    expect(text(element('app-error-state'))).toContain('Serviço fora do ar.');

    element('app-error-state button')?.click();
    await answer({
      storeId: STORE,
      status: 'PENDING',
      canPublish: false,
      visibleInStorefront: false,
    });
    expect(element('app-error-state')).toBeNull();
    expect(text(element('c-callout c-badge'))).toBe('Em análise');
  });

  it('não consulta nada quando a sessão não tem loja', () => {
    session.store.set(undefined);
    render();

    http.expectNone(() => true);
    expect(text(element('app-empty-state'))).toContain('Sua sessão não tem uma loja.');
  });
});
