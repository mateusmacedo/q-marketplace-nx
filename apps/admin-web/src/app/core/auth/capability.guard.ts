import { inject } from '@angular/core';
import { type CanMatchFn, Router } from '@angular/router';
import { ToastService } from '@marketplace/shared-admin-ui';

import type { AppAccess, AppCapability, AppPermission } from './access';
import { AuthSession } from './auth-session';
import { SessionStore } from './session-store';

export const NO_ACCESS_MESSAGE = 'Você não tem acesso a esta área';

/**
 * Libera o grupo de rotas para quem tem a capacidade, segundo `GET /me`; sem ela, volta para `/inicio` com um aviso.
 * Vai no `canMatch`, para a feature nem ser baixada. Sem login, deixa passar: o `authGuard` do layout leva ao provedor
 * e volta para a rota pedida.
 */
export function capabilityGuard(capability: AppCapability): CanMatchFn {
  return accessGuard({ capability });
}

/** Como o `capabilityGuard`, para uma permissão composta que o servidor já resolve (`readTenantCatalog`). */
export function permissionGuard(permission: AppPermission): CanMatchFn {
  return accessGuard({ permission });
}

function accessGuard(access: AppAccess): CanMatchFn {
  return async () => {
    const auth = inject(AuthSession);
    const session = inject(SessionStore);
    const router = inject(Router);
    const toasts = inject(ToastService);
    await auth.ready();
    if (!auth.isAuthenticated()) {
      return true;
    }
    await session.ensureLoaded();
    // WHY: sem a sessão, o layout mostra o erro no lugar da tela, e o "Tentar de novo" refaz a navegação, que passa
    // por esta guarda outra vez com as capacidades já carregadas; com o token recusado, ele oferece "Entrar de novo",
    // em vez de levar ao login daqui, o que entraria em ciclo se o servidor recusasse todo token.
    const status = session.status();
    if (status === 'error' || status === 'expired' || session.allows(access)) {
      return true;
    }
    toasts.warning(NO_ACCESS_MESSAGE, 'Acesso');
    return router.parseUrl('/inicio');
  };
}
