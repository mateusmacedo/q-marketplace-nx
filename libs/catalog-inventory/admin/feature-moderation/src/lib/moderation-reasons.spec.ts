import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationRef, Injector, runInInjectionContext, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideCatalogInventoryApi, type ModerationReasonResponse } from '@marketplace/catalog-inventory-admin-data-access';

import { moderationReasonsResource, type ReasonDecision, reasonOptions } from './moderation-reasons';

const CATALOG: ModerationReasonResponse[] = [
  { code: 'imagem-impropria', label: 'Imagem imprópria', decisions: ['REJECT', 'BLOCK'], position: 0, active: true },
  { code: 'descricao-enganosa', label: 'Descrição enganosa', decisions: ['REJECT'], position: 1, active: true },
  { code: 'produto-proibido', label: 'Produto proibido', decisions: ['BLOCK'], position: 2, active: true },
  { code: 'antigo', label: 'Motivo antigo', decisions: ['REJECT'], position: 3, active: false }
];

describe('motivos de moderação do tenant', () => {
  it('lê os motivos da decisão no servidor e não chama sem decisão', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideCatalogInventoryApi()]
    });
    const http = TestBed.inject(HttpTestingController);
    const decision = signal<ReasonDecision | null>(null);
    const reasons = runInInjectionContext(TestBed.inject(Injector), () => moderationReasonsResource(decision));

    TestBed.tick();
    http.expectNone(() => true);

    decision.set('BLOCK');
    TestBed.tick();
    http.expectOne('/api/catalog-inventory/moderation/reasons?decision=BLOCK').flush(CATALOG.filter(reason => reason.decisions?.includes('BLOCK')));
    await TestBed.inject(ApplicationRef).whenStable();

    expect(reasons.value()?.map(reason => reason.code)).toEqual(['imagem-impropria', 'produto-proibido']);
    http.verify();
  });

  it('monta as opções do modal com os ativos que aceitam a decisão, na ordem do servidor', () => {
    expect(reasonOptions(CATALOG, 'REJECT').map(reason => reason.code)).toEqual(['imagem-impropria', 'descricao-enganosa']);
    expect(reasonOptions(CATALOG, 'BLOCK').map(reason => reason.code)).toEqual(['imagem-impropria', 'produto-proibido']);
    expect(reasonOptions(undefined, 'BLOCK')).toEqual([]);
  });
});
