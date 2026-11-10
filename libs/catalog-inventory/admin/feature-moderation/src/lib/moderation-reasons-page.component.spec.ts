import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import {
  provideCatalogInventoryApi,
  type ModerationReasonResponse,
} from '@marketplace/catalog-inventory-admin-data-access';
import { ToastService } from '@marketplace/shared-admin-ui';

import { ModerationReasonsPageComponent } from './moderation-reasons-page.component';

const API = '/api/catalog-inventory/moderation/reasons';
const LIST = `${API}?includeInactive=true`;

const CATALOG: ModerationReasonResponse[] = [
  {
    code: 'wrong-category',
    label: 'Categoria incorreta',
    decisions: ['REJECT'],
    position: 10,
    active: true,
    version: 2,
  },
  {
    code: 'prohibited-item',
    label: 'Item proibido na plataforma',
    decisions: ['BLOCK'],
    position: 20,
    active: true,
    version: 1,
  },
  {
    code: 'price-out-of-range',
    label: 'Preço fora do padrão',
    decisions: ['REJECT', 'BLOCK'],
    position: 30,
    active: false,
    version: 4,
  },
];

describe('ModerationReasonsPageComponent', () => {
  let fixture: ComponentFixture<ModerationReasonsPageComponent>;
  let http: HttpTestingController;
  let toast: ToastService;

  const element = () => fixture.nativeElement as HTMLElement;
  const rows = () => Array.from(element().querySelectorAll('tbody tr')) as HTMLTableRowElement[];
  const buttonIn = (root: Element, text: string) =>
    (Array.from(root.querySelectorAll('button')) as HTMLButtonElement[]).find((button) =>
      button.textContent?.trim().startsWith(text),
    ) as HTMLButtonElement;
  const menuItem = (row: HTMLTableRowElement, text: string) =>
    (Array.from(row.querySelectorAll('[cDropdownItem]')) as HTMLButtonElement[]).find(
      (item) => item.textContent?.trim() === text,
    ) as HTMLButtonElement;
  const showInactive = () =>
    element().querySelector('#moderation-reasons-show-inactive') as HTMLInputElement;
  const offcanvas = () => element().querySelector('c-offcanvas') as HTMLElement;
  const field = (id: string) =>
    element().querySelector(`#moderation-reason-${id}`) as HTMLInputElement;
  const lastToast = () => toast.toasts().at(-1)?.message;

  /**
   * Espera a cadeia de promessas das chamadas e roda a detecção de mudanças, sem esperar a aplicação ficar estável,
   * porque uma leitura pendente do `httpResource` só termina com o `flush` do teste.
   */
  async function settle(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    TestBed.tick();
  }

  async function render(reasons: ModerationReasonResponse[] = CATALOG): Promise<void> {
    fixture = TestBed.createComponent(ModerationReasonsPageComponent);
    TestBed.tick();
    http.expectOne(LIST).flush(reasons);
    await settle();
  }

  async function reloadList(reasons: ModerationReasonResponse[] = CATALOG): Promise<void> {
    await settle();
    http.expectOne(LIST).flush(reasons);
    await settle();
  }

  async function fill(id: string, value: string): Promise<void> {
    field(id).value = value;
    field(id).dispatchEvent(new Event('input'));
    field(id).dispatchEvent(new Event('blur'));
    await settle();
  }

  async function submit(): Promise<void> {
    buttonIn(offcanvas(), offcanvasSaveLabel()).click();
    await settle();
  }

  function offcanvasSaveLabel(): string {
    return element().querySelector('[cOffcanvasTitle]')?.textContent?.includes('Novo')
      ? 'Criar motivo'
      : 'Salvar';
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ModerationReasonsPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideCatalogInventoryApi()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    toast = TestBed.inject(ToastService);
  });

  afterEach(() => http.verify());

  it('lista os ativos com o código na linha de apoio, as decisões, a situação e a posição; o switch mostra os inativos', async () => {
    await render();

    expect(showInactive().checked).toBe(false);
    expect(
      rows().map((row) =>
        Array.from(row.cells[0].querySelectorAll('div')).map((line) => line.textContent?.trim()),
      ),
    ).toEqual([
      ['Categoria incorreta', 'wrong-category'],
      ['Item proibido na plataforma', 'prohibited-item'],
    ]);
    expect(rows()[0].cells[1].textContent?.trim()).toBe('Rejeitar');
    expect(rows()[0].cells[2].textContent?.trim()).toBe('Ativo');
    expect(rows()[0].cells[3].textContent?.trim()).toBe('10');
    expect(
      Array.from(rows()[0].querySelectorAll('[cDropdownItem]')).map((item) =>
        item.textContent?.trim(),
      ),
    ).toEqual(['Editar', 'Desativar']);
    expect(element().textContent).not.toContain('Excluir');

    showInactive().click();
    await settle();

    expect(rows()).toHaveLength(3);
    expect(
      Array.from(rows()[2].cells[1].querySelectorAll('c-badge')).map((badge) =>
        badge.textContent?.trim(),
      ),
    ).toEqual(['Rejeitar', 'Bloquear']);
    expect(rows()[2].cells[2].textContent?.trim()).toBe('Inativo');
    expect(
      Array.from(rows()[2].querySelectorAll('[cDropdownItem]')).map((item) =>
        item.textContent?.trim(),
      ),
    ).toEqual(['Editar', 'Reativar']);
  });

  it('mostra o estado vazio com Criar motivo e avisa quando só há inativos', async () => {
    await render([]);
    expect(element().textContent).toContain(
      'Nenhum motivo cadastrado. Sem motivos, o moderador decide só com o complemento.',
    );

    buttonIn(element().querySelector('app-empty-state') as Element, 'Criar motivo').click();
    await settle();
    expect(element().querySelector('[cOffcanvasTitle]')?.textContent?.trim()).toBe('Novo motivo');

    fixture.componentInstance.reasons.reload();
    await reloadList([CATALOG[2]]);
    expect(element().textContent).toContain(
      'Nenhum motivo ativo. Ligue Mostrar inativos para ver os desativados.',
    );
  });

  it('cria o motivo sem posição, avisa no toast e recarrega a lista', async () => {
    await render();
    buttonIn(element(), 'Novo motivo').click();
    await settle();

    expect(field('code').readOnly).toBe(false);
    expect(element().textContent).toContain('Sem posição, o motivo entra no fim da lista.');
    await fill('code', 'missing-photo');
    await fill('label', 'Foto ausente');
    (element().querySelector('#moderation-reason-decision-REJECT') as HTMLInputElement).click();
    await settle();
    await submit();

    const request = http.expectOne(API);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      code: 'missing-photo',
      label: 'Foto ausente',
      decisions: ['REJECT'],
    });
    request.flush(
      {
        code: 'missing-photo',
        label: 'Foto ausente',
        decisions: ['REJECT'],
        position: 40,
        active: true,
        version: 0,
      },
      { status: 201, statusText: 'Created', headers: { ETag: '"0"' } },
    );
    await settle();
    await reloadList();

    expect(lastToast()).toBe('Motivo criado. Ele já aparece para o moderador.');
  });

  it('confere os limites do contrato antes de enviar', async () => {
    await render();
    buttonIn(element(), 'Novo motivo').click();
    await settle();

    await fill('code', 'Código Inválido');
    await fill('label', 'x'.repeat(121));
    await submit();

    http.expectNone(API);
    expect(offcanvas().textContent).toContain('Confira o formato deste campo.');
    expect(offcanvas().textContent).toContain('Use no máximo 120 caracteres.');
    expect(offcanvas().textContent).toContain('Marque pelo menos uma decisão.');
  });

  it('no código repetido, mostra a mensagem do servidor e liga Mostrar inativos', async () => {
    await render();
    buttonIn(element(), 'Novo motivo').click();
    await settle();
    await fill('code', 'price-out-of-range');
    await fill('label', 'Preço fora do padrão');
    (element().querySelector('#moderation-reason-decision-BLOCK') as HTMLInputElement).click();
    await submit();

    http.expectOne(API).flush(
      {
        type: 'https://marketplace.dev/problems/duplicate-moderation-reason-code',
        title: 'Código já usado',
        status: 409,
        detail: 'Já existe um motivo com o código price-out-of-range, talvez inativo.',
      },
      { status: 409, statusText: 'Conflict' },
    );
    await settle();
    await reloadList();

    expect(offcanvas().textContent).toContain(
      'Já existe um motivo com o código price-out-of-range, talvez inativo.',
    );
    expect(showInactive().checked).toBe(true);
    expect(rows()).toHaveLength(3);
  });

  it('põe embaixo de cada campo os erros do 400 e mostra a recusa de limite do 422', async () => {
    await render();
    buttonIn(element(), 'Novo motivo').click();
    await settle();
    await fill('code', 'missing-photo');
    await fill('label', 'Foto ausente');
    (element().querySelector('#moderation-reason-decision-REJECT') as HTMLInputElement).click();
    await submit();

    http.expectOne(API).flush(
      {
        title: 'Requisição inválida',
        status: 400,
        errors: [{ field: 'label', message: 'O rótulo não pode ficar em branco' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();
    expect(
      offcanvas().querySelector('#moderation-reason-label + .invalid-feedback')?.textContent,
    ).toContain('O rótulo não pode ficar em branco');

    await fill('label', 'Foto ausente de novo');
    await submit();
    http.expectOne(API).flush(
      {
        type: 'https://marketplace.dev/problems/moderation-reason-limit',
        title: 'Limite de motivos',
        status: 422,
        reasons: ['moderation-reason-limit:100'],
        reasonDetails: [
          {
            code: 'moderation-reason-limit',
            params: ['100'],
            message: 'O tenant já tem 100 motivos ativos. Desative um.',
          },
        ],
      },
      { status: 422, statusText: 'Unprocessable Entity' },
    );
    await settle();

    expect(offcanvas().textContent).toContain('O tenant já tem 100 motivos ativos. Desative um.');
    expect(offcanvas().textContent).not.toContain('moderation-reason-limit');
  });

  it('edita com o ETag lido e grava com If-Match, com o código só leitura', async () => {
    await render();
    menuItem(rows()[0], 'Editar').click();
    await settle();

    http
      .expectOne(`${API}/wrong-category`)
      .flush({ ...CATALOG[0], version: 3 }, { headers: { ETag: '"3"' } });
    await settle();

    expect(element().querySelector('[cOffcanvasTitle]')?.textContent?.trim()).toBe('Editar motivo');
    expect(field('code').value).toBe('wrong-category');
    expect(field('code').readOnly).toBe(true);
    expect(field('label').value).toBe('Categoria incorreta');
    expect(offcanvas().textContent).toContain('As decisões já tomadas guardam o rótulo da época');

    await fill('label', 'Categoria errada');
    await submit();

    const request = http.expectOne(`${API}/wrong-category`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.get('If-Match')).toBe('"3"');
    expect(request.request.body).toEqual({
      label: 'Categoria errada',
      decisions: ['REJECT'],
      position: 10,
    });
    request.flush(
      { ...CATALOG[0], label: 'Categoria errada', version: 4 },
      { headers: { ETag: '"4"' } },
    );
    await settle();
    await reloadList();

    expect(lastToast()).toBe('Motivo atualizado.');
  });

  it('no conflito da edição, mantém o que foi digitado até carregar a versão atual', async () => {
    await render();
    menuItem(rows()[0], 'Editar').click();
    await settle();
    http
      .expectOne(`${API}/wrong-category`)
      .flush({ ...CATALOG[0], version: 3 }, { headers: { ETag: '"3"' } });
    await settle();

    await fill('label', 'Meu rótulo');
    await submit();
    http.expectOne(`${API}/wrong-category`).flush(
      {
        type: 'https://marketplace.dev/problems/version-mismatch',
        title: 'Versão desatualizada',
        status: 412,
      },
      { status: 412, statusText: 'Precondition Failed' },
    );
    await settle();

    expect(offcanvas().textContent).toContain('Outra pessoa alterou este registro');
    expect(field('label').value).toBe('Meu rótulo');

    buttonIn(offcanvas(), 'Carregar versão atual').click();
    await settle();
    http
      .expectOne(`${API}/wrong-category`)
      .flush(
        { ...CATALOG[0], label: 'Categoria trocada', version: 5 },
        { headers: { ETag: '"5"' } },
      );
    await settle();

    expect(field('label').value).toBe('Categoria trocada');
    expect(offcanvas().textContent).not.toContain('Outra pessoa alterou este registro');
  });

  it('desativa depois da confirmação, com If-Match pela versão da lista', async () => {
    await render();
    menuItem(rows()[0], 'Desativar').click();
    await settle();

    const modal = element().querySelector('c-modal') as HTMLElement;
    expect(modal.querySelector('[cModalTitle]')?.textContent?.trim()).toBe(
      'Desativar "Categoria incorreta"?',
    );
    expect(modal.textContent).toContain(
      'O motivo some da lista do moderador e não vale em decisões novas.',
    );
    buttonIn(modal.querySelector('c-modal-footer') as Element, 'Desativar').click();
    await settle();

    const request = http.expectOne(`${API}/wrong-category/deactivate`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('"2"');
    request.flush({ ...CATALOG[0], active: false, version: 3 });
    await settle();
    await reloadList();

    expect(lastToast()).toBe('Motivo desativado. Ele não aparece mais para o moderador.');
  });

  it('no conflito ao desativar, só confirma de novo com a versão atual da lista', async () => {
    await render();
    menuItem(rows()[0], 'Desativar').click();
    await settle();
    const modal = element().querySelector('c-modal') as HTMLElement;
    const confirm = () => buttonIn(modal.querySelector('c-modal-footer') as Element, 'Desativar');
    confirm().click();
    await settle();
    http.expectOne(`${API}/wrong-category/deactivate`).flush(
      {
        type: 'https://marketplace.dev/problems/version-mismatch',
        title: 'Versão desatualizada',
        status: 412,
      },
      { status: 412, statusText: 'Precondition Failed' },
    );
    await settle();
    expect(modal.textContent).toContain('Outra pessoa alterou este registro');

    buttonIn(modal, 'Carregar versão atual').click();
    await settle();
    expect(modal.textContent).toContain('Outra pessoa alterou este registro');
    expect(confirm().disabled).toBe(true);
    confirm().click();
    await settle();
    http.expectNone(`${API}/wrong-category/deactivate`);

    http.expectOne(LIST).flush([{ ...CATALOG[0], version: 7 }, ...CATALOG.slice(1)]);
    await settle();
    expect(modal.textContent).not.toContain('Outra pessoa alterou este registro');
    expect(confirm().disabled).toBe(false);
    confirm().click();
    await settle();
    const retry = http.expectOne(`${API}/wrong-category/deactivate`);
    expect(retry.request.headers.get('If-Match')).toBe('"7"');
    retry.flush({ ...CATALOG[0], active: false, version: 8 });
    await settle();
    await reloadList();
  });

  it('reativa sem confirmação e mostra a recusa do servidor acima da lista', async () => {
    await render();
    showInactive().click();
    await settle();

    menuItem(rows()[2], 'Reativar').click();
    await settle();
    const request = http.expectOne(`${API}/price-out-of-range/activate`);
    expect(request.request.headers.get('If-Match')).toBe('"4"');
    request.flush(
      {
        type: 'https://marketplace.dev/problems/moderation-reason-limit',
        title: 'Limite de motivos',
        status: 422,
        reasonDetails: [
          {
            code: 'moderation-reason-limit',
            message: 'O tenant já tem 100 motivos ativos. Desative um antes.',
          },
        ],
      },
      { status: 422, statusText: 'Unprocessable Entity' },
    );
    await settle();

    expect(element().querySelector('app-problem-alert')?.textContent).toContain(
      'O tenant já tem 100 motivos ativos. Desative um antes.',
    );

    menuItem(rows()[2], 'Reativar').click();
    await settle();
    http
      .expectOne(`${API}/price-out-of-range/activate`)
      .flush({ ...CATALOG[2], active: true, version: 5 });
    await settle();
    await reloadList();

    expect(lastToast()).toBe('Motivo reativado. Ele volta a aparecer para o moderador.');
  });
});
