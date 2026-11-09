import { Injectable, inject } from '@angular/core';
import { OAuthService } from 'angular-oauth2-oidc';

import { authConfig } from './auth.config';

/**
 * Login do painel no provedor OIDC: code flow com PKCE, token no sessionStorage (padrão da biblioteca) e renovação
 * pelo refresh token (ADR 027). Só responde se há sessão; o que o usuário pode fazer vem do servidor
 * (`SessionStore`), e o painel não decide nada pelos papéis ou claims do token; só o nome de exibição vem dele.
 */
@Injectable({ providedIn: 'root' })
export class AuthSession {
  readonly #oauth = inject(OAuthService);
  #ready?: Promise<void>;

  /** Carrega a descoberta do provedor e conclui o retorno do login, uma vez por carga da página. */
  ready(): Promise<void> {
    this.#ready ??= this.#start();
    return this.#ready;
  }

  isAuthenticated(): boolean {
    return this.#oauth.hasValidAccessToken();
  }

  /** Rota que o usuário pediu antes do login; devolvida uma vez só, logo depois do retorno do provedor. */
  takeReturnUrl(): string | undefined {
    const state = this.#oauth.state;
    if (!state) {
      return undefined;
    }
    this.#oauth.state = '';
    return decodeURIComponent(state);
  }

  /** Renova o access token pelo refresh token; `false` quando o provedor recusa (sessão encerrada ou revogada). */
  async refresh(): Promise<boolean> {
    try {
      await this.#oauth.refreshToken();
      return true;
    } catch {
      return false;
    }
  }

  login(returnUrl: string): void {
    this.#oauth.initCodeFlow(returnUrl);
  }

  logout(): void {
    this.#oauth.logOut();
  }

  /** Nome exibido no cabeçalho, do token de identidade. */
  username(): string {
    const claims = this.#oauth.getIdentityClaims() as Record<string, unknown> | null;
    return typeof claims?.['preferred_username'] === 'string' ? claims['preferred_username'] : '';
  }

  async #start(): Promise<void> {
    this.#oauth.configure(authConfig);
    this.#oauth.setupAutomaticSilentRefresh();
    await this.#oauth.loadDiscoveryDocumentAndTryLogin();
  }
}
