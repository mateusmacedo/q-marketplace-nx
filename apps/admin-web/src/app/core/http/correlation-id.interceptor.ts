import { HttpInterceptorFn } from '@angular/common/http';

import { apiUrlPrefixes } from '../auth/auth.config';

export const CORRELATION_ID_HEADER = 'X-Correlation-Id';

/** Cada chamada à API leva um id próprio, que o serviço registra nos logs e nos traces (ADR 021). */
export const correlationIdInterceptor: HttpInterceptorFn = (request, next) => {
  const toApi = apiUrlPrefixes.some((prefix) => request.url.startsWith(prefix));
  if (!toApi || request.headers.has(CORRELATION_ID_HEADER)) {
    return next(request);
  }
  return next(request.clone({ setHeaders: { [CORRELATION_ID_HEADER]: crypto.randomUUID() } }));
};
