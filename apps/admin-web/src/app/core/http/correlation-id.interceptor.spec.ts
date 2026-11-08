import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { CORRELATION_ID_HEADER, correlationIdInterceptor } from './correlation-id.interceptor';

describe('correlationIdInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([correlationIdInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('adds a UUID to API calls', () => {
    http.get('/api/catalog-inventory/categories').subscribe();
    const header = backend.expectOne('/api/catalog-inventory/categories').request.headers.get(CORRELATION_ID_HEADER);
    expect(header).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });

  it('keeps an id set by the caller', () => {
    http.get('/api/catalog-inventory/categories', { headers: { [CORRELATION_ID_HEADER]: 'abc-1' } }).subscribe();
    expect(backend.expectOne('/api/catalog-inventory/categories').request.headers.get(CORRELATION_ID_HEADER)).toBe('abc-1');
  });

  it('leaves other URLs alone', () => {
    http.get('/assets/i18n.json').subscribe();
    expect(backend.expectOne('/assets/i18n.json').request.headers.has(CORRELATION_ID_HEADER)).toBe(false);
  });
});
