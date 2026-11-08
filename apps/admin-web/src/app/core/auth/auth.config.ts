import type { AuthConfig } from 'angular-oauth2-oidc';

// NOTE: valores do Keycloak local (infra/keycloak); a configuração por ambiente, em tempo de execução, fica para
// antes do primeiro deploy (ADR 027).
export const authConfig: AuthConfig = {
  issuer: 'http://localhost:8180/realms/marketplace',
  clientId: 'marketplace-admin-web',
  responseType: 'code',
  scope: 'openid profile',
  redirectUri: `${window.location.origin}/`,
  postLogoutRedirectUri: `${window.location.origin}/`,
  useSilentRefresh: false,
  sessionChecksEnabled: false,
};

/** Só as chamadas à API levam o token; o Keycloak e os arquivos do próprio painel não. */
export const apiUrlPrefixes = ['/api/'];
