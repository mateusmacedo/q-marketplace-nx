import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  type CanMatchFn,
  provideRouter,
  type Route,
  Router,
  type UrlSegment,
  UrlTree,
} from '@angular/router';
import {
  allowsAccess,
  type AccessRequirement,
  type SessionGrants,
  ToastService,
} from '@marketplace/shared-admin-ui';

import { AuthSession } from './auth-session';
import { capabilityGuard, NO_ACCESS_MESSAGE, permissionGuard } from './capability.guard';
import { type SessionStatus, SessionStore } from './session-store';

class FakeAuth {
  authenticated = true;
  ready = () => Promise.resolve();
  isAuthenticated = () => this.authenticated;
}

class FakeSession {
  readonly grants = signal<SessionGrants>({ capabilities: [], permissions: {} });
  readonly status = signal<SessionStatus>('idle');
  readonly loads = signal(0);
  readonly loaded = computed(() => this.loads() > 0);
  ensureLoaded = () => {
    this.loads.update((count) => count + 1);
    if (this.status() === 'idle') {
      this.status.set('ready');
    }
    return Promise.resolve();
  };
  allows = (requirement: AccessRequirement) => allowsAccess(this.grants(), requirement);
}

describe('guardas por capacidade', () => {
  let auth: FakeAuth;
  let session: FakeSession;

  const run = (guard: CanMatchFn) =>
    TestBed.runInInjectionContext(() =>
      guard({} as Route, [] as UrlSegment[], {} as Parameters<CanMatchFn>[2]),
    );
  const url = (result: unknown) =>
    result instanceof UrlTree && TestBed.inject(Router).serializeUrl(result);
  const toasts = () =>
    TestBed.inject(ToastService)
      .toasts()
      .map((toast) => toast.message);

  beforeEach(() => {
    auth = new FakeAuth();
    session = new FakeSession();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthSession, useValue: auth },
        { provide: SessionStore, useValue: session },
      ],
    });
  });

  it('libera quem tem a capacidade, depois de carregar a sessão', async () => {
    session.grants.set({ capabilities: ['MANAGE_TAXONOMY'], permissions: {} });

    expect(await run(capabilityGuard('MANAGE_TAXONOMY'))).toBe(true);
    expect(session.loaded()).toBe(true);
    expect(toasts()).toEqual([]);
  });

  it('volta para o início com o aviso quem não tem a capacidade', async () => {
    session.grants.set({ capabilities: ['MANAGE_OWN_PRODUCTS'], permissions: {} });

    expect(url(await run(capabilityGuard('MANAGE_TAXONOMY')))).toBe('/inicio');
    expect(toasts()).toEqual([NO_ACCESS_MESSAGE]);
  });

  it('confere a permissão composta que o servidor resolveu', async () => {
    session.grants.set({
      capabilities: ['MODERATE_PRODUCTS'],
      permissions: { readTenantCatalog: true },
    });
    expect(await run(permissionGuard('readTenantCatalog'))).toBe(true);

    session.grants.set({
      capabilities: ['MODERATE_PRODUCTS'],
      permissions: { readTenantCatalog: false },
    });
    expect(url(await run(permissionGuard('readTenantCatalog')))).toBe('/inicio');
  });

  it('deixa passar quando a sessão falhou, para o layout mostrar o erro com Tentar de novo', async () => {
    session.status.set('error');
    expect(await run(capabilityGuard('MANAGE_TAXONOMY'))).toBe(true);
    expect(toasts()).toEqual([]);
  });

  it('deixa passar com o token recusado, para o layout oferecer Entrar de novo sem levar ao login em ciclo', async () => {
    session.status.set('expired');
    expect(await run(capabilityGuard('MANAGE_TAXONOMY'))).toBe(true);
    expect(toasts()).toEqual([]);
  });

  it('deixa o login para o authGuard e não carrega a sessão sem login', async () => {
    auth.authenticated = false;
    expect(await run(capabilityGuard('MANAGE_OWN_PRODUCTS'))).toBe(true);
    expect(session.loaded()).toBe(false);
  });
});
