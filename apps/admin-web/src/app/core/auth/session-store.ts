import {
  computed,
  type EnvironmentProviders,
  Injectable,
  inject,
  makeEnvironmentProviders,
  provideAppInitializer,
  signal,
} from '@angular/core';
import { getSession, type SessionResponse } from '@marketplace/catalog-inventory-admin-data-access';
import { AdminSession, NO_GRANTS, type SessionGrants } from '@marketplace/shared-admin-ui';
import { apiData, type Problem, toProblem } from '@marketplace/shared-web-util';

import { AuthSession } from './auth-session';

/**
 * `idle` antes do login; `loading` durante a chamada; `ready` com a sessão; `error` com o problema da chamada;
 * `expired` quando o servidor recusa o token (401) mesmo depois de uma renovação, e só um login novo resolve.
 */
export type SessionStatus = 'idle' | 'loading' | 'ready' | 'error' | 'expired';

interface SessionState {
  readonly status: SessionStatus;
  readonly session?: SessionResponse;
  readonly problem?: Problem;
}

/**
 * Sessão do painel como o servidor responde em `GET /me`: capacidades, permissões compostas, loja e tenant. É o
 * `AdminSession` que as features injetam. Quem tem cada capacidade é decisão do servidor, que traduz os papéis do
 * token; papel novo ou capacidade que muda de papel não mexe no painel.
 */
@Injectable({ providedIn: 'root' })
export class SessionStore extends AdminSession {
  readonly #auth = inject(AuthSession);
  readonly #state = signal<SessionState>({ status: 'idle' });
  #loading?: Promise<void>;

  readonly status = computed(() => this.#state().status);
  readonly problem = computed(() => this.#state().problem);
  override readonly grants = computed<SessionGrants>(() => {
    const session = this.#state().session;
    return session
      ? { capabilities: session.capabilities ?? [], permissions: { ...session.can } }
      : NO_GRANTS;
  });
  override readonly storeId = computed(() => this.#state().session?.storeId ?? undefined);
  override readonly tenantId = computed(() => this.#state().session?.tenantId);

  /** Carrega a sessão se ela ainda não foi pedida; com uma carga em andamento, espera a mesma. */
  ensureLoaded(): Promise<void> {
    return this.#state().status === 'idle' ? this.load() : (this.#loading ?? Promise.resolve());
  }

  /**
   * Carrega `GET /me`, no início do app e no "Tentar de novo". Não rejeita: a falha vira o estado `error`, com o
   * problema da chamada, ou `expired`, no 401 que a renovação do token não resolveu. Quem chama durante a carga espera
   * a mesma chamada.
   */
  load(): Promise<void> {
    this.#loading ??= this.#fetch().finally(() => (this.#loading = undefined));
    return this.#loading;
  }

  async #fetch(): Promise<void> {
    this.#state.update((state) => ({ ...state, status: 'loading', problem: undefined }));
    try {
      this.#state.set({ status: 'ready', session: await this.#request() });
    } catch (error) {
      const problem = toProblem(error);
      this.#state.update((state) => ({
        ...state,
        status: problem.status === 401 ? 'expired' : 'error',
        problem,
      }));
    }
  }

  // WHY: o provedor de login dá folga de relógio ao token, então o painel ainda o considera válido quando o servidor já
  // o recusa com 401 (página recarregada depois de o computador dormir); uma renovação resolve o caso comum.
  async #request(): Promise<SessionResponse> {
    try {
      return await apiData(getSession());
    } catch (error) {
      if (toProblem(error).status !== 401 || !(await this.#auth.refresh())) {
        throw error;
      }
      return apiData(getSession());
    }
  }
}

/**
 * Registra a sessão como o `AdminSession` das features e a carrega no início do app, depois do retorno do login. Vai
 * depois de `provideCatalogInventoryApi()`, que liga o cliente gerado ao `HttpClient`.
 */
export function provideAdminSession(): EnvironmentProviders {
  return makeEnvironmentProviders([
    { provide: AdminSession, useExisting: SessionStore },
    provideAppInitializer(async () => {
      const auth = inject(AuthSession);
      const session = inject(SessionStore);
      try {
        await auth.ready();
      } catch {
        // WHY: a falha do provedor de login fica para o authGuard, que a trata na navegação como antes.
        return;
      }
      if (auth.isAuthenticated()) {
        await session.load();
      }
    }),
  ]);
}
