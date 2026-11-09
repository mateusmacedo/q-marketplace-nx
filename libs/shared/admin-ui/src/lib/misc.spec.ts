import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { NAV_BADGE_MAX_AGE_MS, NavBadgeService } from './nav/nav-badge.service';
import { NumberBlockComponent } from './number-block/number-block.component';
import { DateTimePipe } from './pipes/date-time.pipe';
import { MoneyPipe } from './pipes/money.pipe';
import { RelativeTimePipe } from './pipes/relative-time.pipe';
import { AdminSession, allowsAccess, NO_GRANTS, type SessionGrants } from './session/admin-session';
import { EmptyStateComponent } from './states/empty-state.component';
import { ErrorStateComponent } from './states/error-state.component';
import { LoadingStateComponent } from './states/loading-state.component';

class FakeSession extends AdminSession {
  readonly grants = signal<SessionGrants>({
    capabilities: ['MANAGE_OWN_PRODUCTS', 'CAPACIDADE_NOVA'],
    permissions: { readTenantCatalog: false }
  });
  readonly storeId = signal<string | undefined>('22222222-2222-4222-8222-222222222222');
  readonly tenantId = signal<string | undefined>(undefined);
}

describe('pipes', () => {
  it('formatam data, data relativa e moeda pelo web-util', () => {
    expect(new DateTimePipe().transform('2026-11-08T17:02:00Z')).toBe('08/11/2026 14:02');
    expect(new DateTimePipe().transform('2026-11-08T17:02:00Z', 'short')).toBe('08/11 14:02');
    expect(new DateTimePipe().transform(null, 'time')).toBe('');
    expect(new RelativeTimePipe().transform('2026-11-08T14:00:00Z', '2026-11-08T15:00:00Z')).toBe('há 1 hora');
    expect(new MoneyPipe().transform('49.9', 'BRL')).toBe('R$ 49,90');
    expect(new MoneyPipe().transform(10, undefined)).toBe('');
  });
});

describe('AdminSession', () => {
  it('confere a capacidade e a permissão composta que o servidor mandou', () => {
    const session = new FakeSession();
    expect(session.hasCapability('MANAGE_OWN_PRODUCTS')).toBe(true);
    expect(session.hasCapability('MANAGE_TAXONOMY')).toBe(false);
    expect(session.can('readTenantCatalog')).toBe(false);
    expect(session.allows({ capability: 'MANAGE_OWN_PRODUCTS', permission: 'readTenantCatalog' })).toBe(false);
    expect(session.allows()).toBe(true);
    expect(session.allows({})).toBe(true);
  });

  it('não libera nada sem sessão e ignora permissão desconhecida', () => {
    expect(allowsAccess(NO_GRANTS, { capability: 'MANAGE_OWN_PRODUCTS' })).toBe(false);
    expect(allowsAccess(NO_GRANTS, { permission: 'readTenantCatalog' })).toBe(false);
    expect(allowsAccess({ capabilities: [], permissions: { readTenantCatalog: true } }, { permission: 'outra' })).toBe(false);
    expect(allowsAccess(NO_GRANTS, null)).toBe(true);
  });
});

describe('NavBadgeService', () => {
  it('guarda as contagens por chave e esquece a zerada', () => {
    const badges = TestBed.inject(NavBadgeService);
    badges.set('moderation-queue', 4);
    expect(badges.counts()).toEqual({ 'moderation-queue': 4 });
    badges.set('moderation-queue', 0);
    expect(badges.counts()).toEqual({});
  });

  describe('refresh', () => {
    let now: number;

    beforeEach(() => {
      now = Date.parse('2026-11-09T12:00:00Z');
      vi.spyOn(Date, 'now').mockImplementation(() => now);
    });

    afterEach(() => vi.restoreAllMocks());

    it('reaproveita a leitura dentro da janela e lê de novo quando ela vence', async () => {
      const badges = TestBed.inject(NavBadgeService);
      const load = vi.fn().mockResolvedValueOnce(4).mockResolvedValueOnce(2);

      await badges.refresh('moderation-queue', load);
      expect(badges.counts()).toEqual({ 'moderation-queue': 4 });

      now += NAV_BADGE_MAX_AGE_MS - 1;
      await badges.refresh('moderation-queue', load);
      expect(load).toHaveBeenCalledTimes(1);

      now += 1;
      await badges.refresh('moderation-queue', load);
      expect(load).toHaveBeenCalledTimes(2);
      expect(badges.counts()).toEqual({ 'moderation-queue': 2 });
    });

    it('lê de novo dentro da janela com force, e a resposta atrasada da leitura anterior não sobrescreve', async () => {
      const badges = TestBed.inject(NavBadgeService);
      let answerFirst: (count: number) => void = () => undefined;
      const first = badges.refresh('moderation-queue', () => new Promise<number>(resolve => (answerFirst = resolve)));
      const second = badges.refresh('moderation-queue', () => Promise.resolve(3), { force: true });

      await second;
      answerFirst(9);
      await first;
      expect(badges.counts()).toEqual({ 'moderation-queue': 3 });
    });

    it('junta as chamadas com a leitura em andamento', async () => {
      const badges = TestBed.inject(NavBadgeService);
      const load = vi.fn().mockResolvedValue(5);

      await Promise.all([badges.refresh('moderation-queue', load), badges.refresh('moderation-queue', load)]);
      expect(load).toHaveBeenCalledTimes(1);
    });

    it('mantém o badge na falha e tenta de novo na próxima chamada', async () => {
      const badges = TestBed.inject(NavBadgeService);
      badges.set('moderation-queue', 4);
      const load = vi.fn().mockRejectedValueOnce(new Error('fora do ar')).mockResolvedValueOnce(6);

      await badges.refresh('moderation-queue', load);
      expect(badges.counts()).toEqual({ 'moderation-queue': 4 });

      await badges.refresh('moderation-queue', load);
      expect(badges.counts()).toEqual({ 'moderation-queue': 6 });
    });
  });
});

describe('blocos e estados', () => {
  it('monta o número em bloco com a borda da cor', async () => {
    const fixture = TestBed.createComponent(NumberBlockComponent);
    fixture.componentRef.setInput('label', 'Falhas');
    fixture.componentRef.setInput('value', 5);
    fixture.componentRef.setInput('color', 'danger');
    await fixture.whenStable();

    const block = fixture.nativeElement.firstElementChild as HTMLElement;
    expect(Array.from(block.classList).sort()).toEqual(
      ['border-start', 'border-start-4', 'border-start-danger', 'px-3', 'py-1']
    );
    expect(Array.from(block.children).map(child => child.textContent?.trim())).toEqual(['Falhas', '5']);
  });

  it('mostra o estado vazio com o texto', async () => {
    const fixture = TestBed.createComponent(EmptyStateComponent);
    fixture.componentRef.setInput('message', 'Nenhum produto em rascunho.');
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Nenhum produto em rascunho.');
  });

  it('mostra as linhas do placeholder no carregamento', async () => {
    const fixture = TestBed.createComponent(LoadingStateComponent);
    fixture.componentRef.setInput('rows', 3);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelectorAll('.placeholder').length).toBe(3);
    expect(fixture.nativeElement.querySelector('[role="status"]')?.getAttribute('aria-busy')).toBe('true');
  });

  it('oferece Tentar de novo no erro', async () => {
    const fixture = TestBed.createComponent(ErrorStateComponent);
    fixture.componentRef.setInput('problem', { type: 'network-error', status: 0 });
    let retries = 0;
    fixture.componentInstance.retry.subscribe(() => retries++);
    await fixture.whenStable();

    const element: HTMLElement = fixture.nativeElement;
    expect(element.textContent).toContain('Não foi possível falar com o servidor.');
    const button = element.querySelector('button') as HTMLButtonElement;
    expect(button.textContent?.trim()).toBe('Tentar de novo');
    button.click();
    expect(retries).toBe(1);

    fixture.componentRef.setInput('actionLabel', 'Entrar de novo');
    await fixture.whenStable();
    expect(button.textContent?.trim()).toBe('Entrar de novo');
  });

  it('mostra no erro a mensagem do servidor e, sem ela, o detail', async () => {
    const fixture = TestBed.createComponent(ErrorStateComponent);
    fixture.componentRef.setInput('problem', {
      type: 'https://marketplace.dev/problems/category-too-deep',
      status: 409,
      detail: 'Profundidade 7 acima do máximo',
      reasonDetails: [{ code: 'category-too-deep', params: ['6'], message: 'A árvore aceita até 6 níveis.' }]
    });
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.small')?.textContent?.trim()).toBe('A árvore aceita até 6 níveis.');

    fixture.componentRef.setInput('problem', { type: 'https://marketplace.dev/problems/not-found', status: 404, detail: 'Produto não encontrado' });
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.small')?.textContent?.trim()).toBe('Produto não encontrado');
  });
});
