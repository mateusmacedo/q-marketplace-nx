import { provideHttpClient, withInterceptors, withInterceptorsFromDi } from '@angular/common/http';
import { ApplicationConfig } from '@angular/core';
import {
  provideRouter,
  withEnabledBlockingInitialNavigation,
  withInMemoryScrolling,
  withRouterConfig,
  withViewTransitions,
} from '@angular/router';
import { IconSetService } from '@coreui/icons-angular';
import { provideCatalogInventoryApi } from '@marketplace/catalog-inventory-admin-data-access';
import { provideOAuthClient } from 'angular-oauth2-oidc';

import { routes } from './app.routes';
import { apiUrlPrefixes } from './core/auth/auth.config';
import { correlationIdInterceptor } from './core/http/correlation-id.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(
      routes,
      withRouterConfig({
        onSameUrlNavigation: 'reload',
      }),
      withInMemoryScrolling({
        scrollPositionRestoration: 'top',
        anchorScrolling: 'enabled',
      }),
      withEnabledBlockingInitialNavigation(),
      withViewTransitions(),
    ),
    // WHY: o interceptor do token da angular-oauth2-oidc é registrado por DI (HTTP_INTERCEPTORS).
    provideHttpClient(withInterceptors([correlationIdInterceptor]), withInterceptorsFromDi()),
    provideOAuthClient({ resourceServer: { allowedUrls: apiUrlPrefixes, sendAccessToken: true } }),
    provideCatalogInventoryApi(),
    IconSetService,
  ],
};
