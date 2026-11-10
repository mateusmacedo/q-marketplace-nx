import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationInitStatus } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideCatalogInventoryApi } from '@marketplace/catalog-inventory-admin-data-access';
import { AdminSession } from '@marketplace/shared-admin-ui';

import { AuthSession } from './auth-session';
import { provideAdminSession, SessionStore } from './session-store';

const ME = '/api/catalog-inventory/me';
const SELLER = {
  subject: 'u-1',
  tenantId: '11111111-1111-4111-8111-111111111111',
  storeId: '22222222-2222-4222-8222-222222222222',
  capabilities: ['MANAGE_OWN_PRODUCTS'],
  can: { readTenantCatalog: false },
};

class FakeAuth {
  authenticated = true;
  renewed = true;
  refreshes = 0;
  ready = () => Promise.resolve();
  isAuthenticated = () => this.authenticated;
  refresh = () => {
    this.refreshes++;
    return Promise.resolve(this.renewed);
  };
}

const UNAUTHORIZED = { status: 401, statusText: 'Unauthorized' };

describe('SessionStore', () => {
  let auth: FakeAuth;
  let http: HttpTestingController;

  const configure = () => {
    auth = new FakeAuth();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideCatalogInventoryApi(),
        provideAdminSession(),
        { provide: AuthSession, useValue: auth },
      ],
    });
  };
  const request = () => vi.waitFor(() => http.expectOne(ME));
  const started = async () => {
    http = TestBed.inject(HttpTestingController);
    return TestBed.inject(ApplicationInitStatus).donePromise;
  };

  afterEach(() => http.verify());

  it('carrega GET /me no início do app e responde capacidades, permissões, loja e tenant', async () => {
    configure();
    const done = started();
    (await request()).flush(SELLER);
    await done;

    const session = TestBed.inject(SessionStore);
    expect(session.status()).toBe('ready');
    expect(session.grants()).toEqual({
      capabilities: ['MANAGE_OWN_PRODUCTS'],
      permissions: { readTenantCatalog: false },
    });
    expect(session.hasCapability('MANAGE_OWN_PRODUCTS')).toBe(true);
    expect(session.can('readTenantCatalog')).toBe(false);
    expect(session.storeId()).toBe(SELLER.storeId);
    expect(session.tenantId()).toBe(SELLER.tenantId);
    expect(TestBed.inject(AdminSession)).toBe(session);
  });

  it('não chama /me antes do login', async () => {
    configure();
    auth.authenticated = false;
    await started();

    expect(TestBed.inject(SessionStore).status()).toBe('idle');
  });

  it('guarda o problema da falha e carrega de novo no Tentar de novo', async () => {
    configure();
    const done = started();
    (await request()).flush(
      {
        type: 'https://marketplace.dev/problems/service-unavailable',
        title: 'Indisponível',
        detail: 'Banco fora do ar',
      },
      { status: 503, statusText: 'Service Unavailable' },
    );
    await done;

    const session = TestBed.inject(SessionStore);
    expect(session.status()).toBe('error');
    expect(session.problem()).toEqual(
      expect.objectContaining({ status: 503, detail: 'Banco fora do ar' }),
    );
    expect(session.grants().capabilities).toEqual([]);

    const retry = session.load();
    expect(session.status()).toBe('loading');
    (await request()).flush({
      ...SELLER,
      storeId: null,
      capabilities: ['MANAGE_TAXONOMY'],
      can: { readTenantCatalog: true },
    });
    await retry;

    expect(session.status()).toBe('ready');
    expect(session.problem()).toBeUndefined();
    expect(session.allows({ capability: 'MANAGE_TAXONOMY', permission: 'readTenantCatalog' })).toBe(
      true,
    );
    expect(session.storeId()).toBeUndefined();
  });

  it('renova o token uma vez no 401 e repete a chamada', async () => {
    configure();
    const done = started();
    (await request()).flush(null, UNAUTHORIZED);
    (await request()).flush(SELLER);
    await done;

    const session = TestBed.inject(SessionStore);
    expect(auth.refreshes).toBe(1);
    expect(session.status()).toBe('ready');
    expect(session.problem()).toBeUndefined();
  });

  it('pede login de novo quando o provedor recusa a renovação, sem repetir a chamada', async () => {
    configure();
    auth.renewed = false;
    const done = started();
    (await request()).flush(null, UNAUTHORIZED);
    await done;

    const session = TestBed.inject(SessionStore);
    expect(auth.refreshes).toBe(1);
    expect(session.status()).toBe('expired');
    expect(session.problem()).toEqual(expect.objectContaining({ status: 401 }));
    expect(session.grants().capabilities).toEqual([]);
  });

  it('pede login de novo quando o servidor recusa também o token renovado', async () => {
    configure();
    const done = started();
    (await request()).flush(null, UNAUTHORIZED);
    (await request()).flush(null, UNAUTHORIZED);
    await done;

    expect(auth.refreshes).toBe(1);
    expect(TestBed.inject(SessionStore).status()).toBe('expired');
  });

  it('não renova o token em outras falhas', async () => {
    configure();
    const done = started();
    (await request()).flush(
      {
        type: 'https://marketplace.dev/problems/tenant-not-authorized',
        title: 'Sem tenant',
        status: 403,
      },
      { status: 403, statusText: 'Forbidden' },
    );
    await done;

    expect(auth.refreshes).toBe(0);
    expect(TestBed.inject(SessionStore).status()).toBe('error');
  });

  it('faz uma chamada só quando a guarda e o início pedem a sessão juntos', async () => {
    configure();
    auth.authenticated = false;
    await started();
    const session = TestBed.inject(SessionStore);

    const first = session.ensureLoaded();
    const second = session.ensureLoaded();
    (await request()).flush(SELLER);
    await Promise.all([first, second]);

    expect(session.status()).toBe('ready');
    await session.ensureLoaded();
  });
});
