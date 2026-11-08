import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthSession } from './auth-session';

/** Sem token válido, manda para o login do provedor e volta para a rota pedida. */
export const authGuard: CanActivateFn = async (_route, state) => {
  const session = inject(AuthSession);
  const router = inject(Router);
  await session.ready();
  if (!session.isAuthenticated()) {
    session.login(state.url);
    return false;
  }
  const returnUrl = session.takeReturnUrl();
  return returnUrl === undefined ? true : router.parseUrl(returnUrl);
};
