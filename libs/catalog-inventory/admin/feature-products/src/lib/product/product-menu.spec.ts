import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideCatalogInventoryApi } from '@marketplace/catalog-inventory-admin-data-access';

import { productMenu } from '../product-actions';
import {
  actionFailureHeading,
  actionMenuItems,
  productActionPresentation,
  refusalIssues,
  runProductAction
} from './product-menu';

const API = '/api/catalog-inventory';

describe('ações do produto na tela', () => {
  it('leva o menu do produto ao app-action-menu, com a barrada desabilitada e o motivo do servidor', () => {
    const items = actionMenuItems(
      productMenu(
        {
          actions: ['ARCHIVE', 'PAUSE', 'EDIT', 'APPROVE'],
          blockedActions: [{ action: 'SUBMIT', reason: { code: 'product-not-publishable', message: 'Há pendências.' } }]
        },
        'detail'
      )
    );

    expect(items.map(item => [item.id, item.label, item.danger, item.disabledReason])).toEqual([
      ['SUBMIT', 'Enviar para moderação', false, 'Há pendências.'],
      ['PAUSE', 'Pausar', false, undefined],
      ['ARCHIVE', 'Arquivar', true, undefined]
    ]);
    expect(productActionPresentation('UNBLOCK')?.label).toBe('Desbloquear');
    expect(productActionPresentation('APPROVE')).toBeUndefined();
    const submit = productActionPresentation('SUBMIT');
    expect(submit && actionFailureHeading(submit)).toBe('Não foi possível enviar para moderação');
  });

  it('põe na recusa do envio o campo da pendência com o mesmo código e os mesmos valores', () => {
    const issues = refusalIssues(
      {
        status: 422,
        reasonDetails: [
          { code: 'missing-offer', params: ['CAM-P'], message: 'Defina o preço do SKU CAM-P.' },
          { code: 'missing-attribute', params: ['material'], message: 'Preencha Material.' }
        ]
      },
      [
        { code: 'missing-offer', params: ['CAM-P'], field: 'skus[CAM-P].offer' },
        { code: 'missing-offer', params: ['CAM-M'], field: 'skus[CAM-M].offer' }
      ]
    );

    expect(issues).toEqual([
      { code: 'missing-offer', params: ['CAM-P'], message: 'Defina o preço do SKU CAM-P.', field: 'skus[CAM-P].offer' },
      { code: 'missing-attribute', params: ['material'], message: 'Preencha Material.', field: undefined }
    ]);
  });

  describe('chamada de cada ação', () => {
    let http: HttpTestingController;

    beforeEach(() => {
      TestBed.configureTestingModule({
        providers: [provideHttpClient(), provideHttpClientTesting(), provideCatalogInventoryApi()]
      });
      http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => http.verify());

    const cases: [Parameters<typeof runProductAction>[0], string, string][] = [
      ['SUBMIT', 'POST', `${API}/products/p1/submit`],
      ['PAUSE', 'POST', `${API}/products/p1/pause`],
      ['RESUME', 'POST', `${API}/products/p1/resume`],
      ['ARCHIVE', 'DELETE', `${API}/products/p1`],
      ['UNBLOCK', 'POST', `${API}/moderation/products/p1/unblock`]
    ];

    for (const [code, method, url] of cases) {
      it(`${code} chama ${method} ${url.replace(API, '')} com o If-Match e devolve a versão nova`, async () => {
        const result = runProductAction(code, 'p1', '"3"');
        const request = await vi.waitFor(() => http.expectOne(url));
        expect(request.request.method).toBe(method);
        expect(request.request.headers.get('If-Match')).toBe('"3"');
        request.flush({ id: 'p1', version: 4 }, { headers: { ETag: '"4"' } });

        await expect(result).resolves.toEqual({ data: { id: 'p1', version: 4 }, version: '"4"' });
      });
    }

    it('lança o problema normalizado quando o servidor recusa', async () => {
      const result = runProductAction('PAUSE', 'p1', undefined);
      const request = await vi.waitFor(() => http.expectOne(`${API}/products/p1/pause`));
      expect(request.request.headers.has('If-Match')).toBe(false);
      request.flush(
        { type: 'https://marketplace.dev/problems/version-mismatch', title: 'Versão divergente', status: 412 },
        { status: 412, statusText: 'Precondition Failed' }
      );

      await expect(result).rejects.toMatchObject({ status: 412, title: 'Versão divergente' });
    });

    it('não tem chamada para Editar, que na tela do produto já é o conteúdo', async () => {
      await expect(runProductAction('EDIT', 'p1', '"3"')).rejects.toThrow();
      http.expectNone(() => true);
    });
  });
});
