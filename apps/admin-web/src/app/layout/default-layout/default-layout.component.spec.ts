import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideOAuthClient } from 'angular-oauth2-oidc';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { provideCatalogInventoryApi } from '@marketplace/catalog-inventory-admin-data-access';
import { type SessionGrants } from '@marketplace/shared-admin-ui';
import { type Problem } from '@marketplace/shared-web-util';

import { IconSetService } from '@coreui/icons-angular';
import { iconSubset } from '../../icons/icon-subset';
import { AuthSession } from '../../core/auth/auth-session';
import { type SessionStatus, SessionStore } from '../../core/auth/session-store';
import { DefaultLayoutComponent } from './default-layout.component';

describe('DefaultLayoutComponent', () => {
  let component: DefaultLayoutComponent;
  let fixture: ComponentFixture<DefaultLayoutComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DefaultLayoutComponent],
      providers: [IconSetService, provideRouter([]), provideHttpClient(), provideOAuthClient()],
    }).compileComponents();

    TestBed.inject(IconSetService).icons = { ...iconSubset };

    fixture = TestBed.createComponent(DefaultLayoutComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('mostra só o Início sem sessão carregada', () => {
    expect(component.navItems().map((item) => item.name)).toEqual(['Início']);
  });

  it('tem o host dos toasts e o modal do aviso de saída', () => {
    expect(fixture.nativeElement.querySelector('app-toast-host c-toaster')).toBeTruthy();
    expect(
      fixture.nativeElement.querySelector('app-leave-confirm-host app-confirm-modal'),
    ).toBeTruthy();
  });

  it('should render the sidebar, header, footer and router outlet', () => {
    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('c-sidebar')).toBeTruthy();
    expect(element.querySelector('app-default-header')).toBeTruthy();
    expect(element.querySelector('app-default-footer')).toBeTruthy();
    expect(element.querySelector('router-outlet')).toBeTruthy();
  });
});

describe('DefaultLayoutComponent com a sessão do servidor', () => {
  class FakeSession {
    readonly status = signal<SessionStatus>('ready');
    readonly problem = signal<Problem | undefined>(undefined);
    readonly grants = signal<SessionGrants>({ capabilities: ['MANAGE_TAXONOMY'], permissions: {} });
    loads = 0;
    load = () => {
      this.loads++;
      this.status.set('ready');
      return Promise.resolve();
    };
  }

  let session: FakeSession;
  let fixture: ComponentFixture<DefaultLayoutComponent>;

  beforeEach(async () => {
    session = new FakeSession();
    await TestBed.configureTestingModule({
      imports: [DefaultLayoutComponent],
      providers: [
        IconSetService,
        provideRouter([]),
        provideHttpClient(),
        provideOAuthClient(),
        { provide: SessionStore, useValue: session },
      ],
    }).compileComponents();
    TestBed.inject(IconSetService).icons = { ...iconSubset };
    fixture = TestBed.createComponent(DefaultLayoutComponent);
  });

  it('monta o menu com as capacidades da sessão', async () => {
    await fixture.whenStable();
    expect(fixture.componentInstance.navItems().map((item) => item.name)).toEqual([
      'Início',
      'Plataforma',
      'Categorias',
    ]);
  });

  it('mostra o carregamento no lugar da tela enquanto a sessão carrega', async () => {
    session.status.set('loading');
    await fixture.whenStable();
    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('app-loading-state [aria-busy="true"]')).toBeTruthy();
    expect(element.querySelector('router-outlet')).toBeNull();
  });

  it('mostra o erro da sessão e tenta de novo, refazendo a navegação', async () => {
    session.status.set('error');
    session.problem.set({
      type: 'https://marketplace.dev/problems/tenant-not-authorized',
      status: 403,
      detail: 'Token sem tenant',
    });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    await fixture.whenStable();

    const element: HTMLElement = fixture.nativeElement;
    expect(element.textContent).toContain('Não foi possível carregar suas permissões.');
    expect(element.textContent).toContain('Token sem tenant');
    expect(element.querySelector('router-outlet')).toBeNull();

    await fixture.componentInstance.retrySession();
    await fixture.whenStable();
    expect(session.loads).toBe(1);
    expect(navigate).toHaveBeenCalledWith('/', { onSameUrlNavigation: 'reload' });
    expect(element.querySelector('router-outlet')).toBeTruthy();
  });

  describe('badge da Fila', () => {
    const STATS = '/api/catalog-inventory/stats/moderation/queue';
    let http: HttpTestingController;
    let router: Router;
    let layout: ComponentFixture<DefaultLayoutComponent>;
    let offset: number;
    let hidden: boolean;

    const stats = () =>
      vi.waitFor(() => {
        TestBed.tick();
        return http.expectOne(STATS);
      });
    const noStats = async () => {
      await new Promise((resolve) => setTimeout(resolve));
      TestBed.tick();
      http.expectNone(STATS);
    };
    const badge = () =>
      layout.componentInstance.navItems().find((item) => item.url === '/moderacao/fila')?.badge
        ?.text;
    const visibility = (isHidden: boolean) => {
      hidden = isHidden;
      document.dispatchEvent(new Event('visibilitychange'));
    };

    function create(capabilities: string[] = ['MODERATE_PRODUCTS']): void {
      TestBed.resetTestingModule();
      session = new FakeSession();
      session.grants.set({ capabilities, permissions: {} });
      TestBed.configureTestingModule({
        imports: [DefaultLayoutComponent],
        providers: [
          IconSetService,
          provideRouter([
            { path: 'inicio', children: [] },
            { path: 'moderacao/fila', children: [] },
          ]),
          provideHttpClient(),
          provideHttpClientTesting(),
          provideCatalogInventoryApi(),
          provideOAuthClient(),
          { provide: SessionStore, useValue: session },
        ],
      });
      TestBed.inject(IconSetService).icons = { ...iconSubset };
      http = TestBed.inject(HttpTestingController);
      router = TestBed.inject(Router);
      layout = TestBed.createComponent(DefaultLayoutComponent);
    }

    beforeEach(() => {
      offset = 0;
      hidden = false;
      const now = Date.now.bind(Date);
      vi.spyOn(Date, 'now').mockImplementation(() => now() + offset);
      Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
    });

    afterEach(() => {
      Reflect.deleteProperty(document, 'hidden');
      vi.restoreAllMocks();
      http.verify();
    });

    it('alimenta o badge fora da fila para quem modera e reaproveita a leitura por 60 s', async () => {
      create();
      (await stats()).flush({ count: 4 });
      await vi.waitFor(() => expect(badge()).toBe('4'));

      await router.navigateByUrl('/moderacao/fila');
      await noStats();
      await router.navigateByUrl('/inicio');
      await noStats();

      offset = 60_000;
      await router.navigateByUrl('/moderacao/fila');
      await noStats();
      await router.navigateByUrl('/inicio');
      (await stats()).flush({ count: 2 });
      await vi.waitFor(() => expect(badge()).toBe('2'));
    });

    it('não lê o badge quando a navegação só muda a query string, mesmo com a leitura vencida', async () => {
      create();
      (await stats()).flush({ count: 4 });
      await router.navigateByUrl('/inicio');
      await noStats();

      offset = 60_000;
      await router.navigateByUrl('/inicio?cursor=abc');
      await noStats();
      await router.navigateByUrl('/inicio?cursor=abc&limit=50');
      await noStats();
      expect(badge()).toBe('4');
    });

    it('lê o badge de novo ao voltar para a aba só quando a leitura venceu', async () => {
      create();
      (await stats()).flush({ count: 4 });
      await vi.waitFor(() => expect(badge()).toBe('4'));

      visibility(false);
      await noStats();

      offset = 60_000;
      visibility(true);
      await noStats();
      visibility(false);
      (await stats()).flush({ count: 3 });
      await vi.waitFor(() => expect(badge()).toBe('3'));
    });

    it('não lê o badge sem o item da Fila no menu', async () => {
      create(['MANAGE_TAXONOMY']);
      await router.navigateByUrl('/inicio');
      await noStats();

      offset = 60_000;
      visibility(false);
      await noStats();
      expect(badge()).toBeUndefined();
    });
  });

  it('oferece Entrar de novo quando o servidor recusou o token, voltando para a rota atual', async () => {
    session.status.set('expired');
    session.problem.set({ type: 'http-401', status: 401 });
    const login = vi
      .spyOn(TestBed.inject(AuthSession), 'login')
      .mockImplementation(() => undefined);
    await fixture.whenStable();

    const element: HTMLElement = fixture.nativeElement;
    expect(element.textContent).toContain('Sua sessão expirou.');
    expect(element.textContent).not.toContain('HTTP 401');
    expect(element.querySelector('router-outlet')).toBeNull();
    const button = element.querySelector('app-error-state button') as HTMLButtonElement;
    expect(button.textContent?.trim()).toBe('Entrar de novo');

    button.click();
    expect(login).toHaveBeenCalledWith('/');
    expect(session.loads).toBe(0);
  });
});
