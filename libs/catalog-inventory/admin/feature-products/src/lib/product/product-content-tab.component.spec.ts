import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, inject } from '@angular/core';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import {
  type CategoryResponse,
  provideCatalogInventoryApi,
  type ProductResponse
} from '@marketplace/catalog-inventory-admin-data-access';
import { ToastService, UNSAVED_CHANGES_WARNING } from '@marketplace/shared-admin-ui';

import { ProductContentTabComponent } from './product-content-tab.component';
import { ProductStore } from './product-store';

const API = '/api/catalog-inventory';

const CAMISAS: CategoryResponse = {
  id: 'c1',
  name: 'Camisas',
  ancestors: [{ id: 'c0', name: 'Vestuário' }],
  effectiveAttributes: [
    { code: 'material', label: 'Material', type: 'OPTION', required: true, options: ['Algodão', 'Poliéster'] },
    { code: 'gola', label: 'Gola', type: 'TEXT' },
    { code: 'gramatura', label: 'Gramatura', type: 'INTEGER' },
    { code: 'organico', label: 'Orgânico', type: 'BOOLEAN' },
    { code: 'tamanho', label: 'Tamanho', type: 'OPTION', variant: true, options: ['P', 'M'] }
  ]
};

const CALCAS: CategoryResponse = {
  id: 'c2',
  name: 'Calças',
  ancestors: [{ id: 'c0', name: 'Vestuário' }],
  effectiveAttributes: [
    { code: 'material', label: 'Material', type: 'OPTION', required: true, options: ['Algodão', 'Jeans'] },
    { code: 'cintura', label: 'Cintura (cm)', type: 'DECIMAL', required: true }
  ]
};

const DRAFT: ProductResponse = {
  id: 'p1',
  title: 'Camiseta Basic',
  description: 'Malha leve',
  categoryId: 'c1',
  currency: 'BRL',
  status: 'DRAFT',
  version: 3,
  attributes: { material: 'Algodão', gola: 'Redonda', gramatura: '180' },
  actions: ['EDIT', 'CHANGE_CATEGORY', 'REMOVE_SKU', 'SUBMIT'],
  saveReturnsToModeration: false
};

@Component({
  imports: [ProductContentTabComponent],
  providers: [ProductStore],
  template: '<app-product-content-tab />'
})
class Host {
  readonly store = inject(ProductStore);
}

describe('ProductContentTabComponent', () => {
  let fixture: ComponentFixture<Host>;
  let http: HttpTestingController;
  let store: ProductStore;
  let tab: ProductContentTabComponent;

  const element = () => fixture.nativeElement as HTMLElement;
  const text = () => element().textContent?.replace(/\s+/g, ' ') ?? '';
  const render = () => TestBed.tick();
  const field = <T extends HTMLElement>(id: string) => element().querySelector(`[id="product-field-${id}"]`) as T;
  const button = (root: ParentNode, label: string) =>
    (Array.from(root.querySelectorAll('button')) as HTMLButtonElement[]).find(
      item => item.textContent?.replace(/\s+/g, ' ').trim() === label
    ) as HTMLButtonElement;
  const type = (input: HTMLInputElement | HTMLTextAreaElement, value: string) => {
    input.value = value;
    input.dispatchEvent(new Event('input'));
    render();
  };
  const choose = (select: HTMLSelectElement, value: string) => {
    select.value = value;
    select.dispatchEvent(new Event('change'));
    render();
  };
  const flushCategory = async (category: CategoryResponse) => {
    const request = await vi.waitFor(() => {
      render();
      return http.expectOne(`${API}/categories/${category.id}`);
    });
    request.flush(category);
    await vi.waitFor(() => {
      render();
      expect(element().querySelector('[formgroupname="attributes"]')).toBeTruthy();
    });
  };
  const form = () => element().querySelector('form') as HTMLFormElement;
  const submit = () => {
    form().dispatchEvent(new Event('submit'));
    render();
  };
  const treeRequest = () =>
    vi.waitFor(() => http.expectOne(req => req.url.startsWith(`${API}/categories/tree?`)));

  function mount(product: ProductResponse = DRAFT) {
    TestBed.configureTestingModule({
      imports: [Host],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideCatalogInventoryApi()]
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    store = fixture.componentInstance.store;
    store.accept({ data: product, version: `"${product.version}"` });
    render();
    tab = fixture.debugElement.query(By.directive(ProductContentTabComponent)).componentInstance;
  }

  async function open(product: ProductResponse = DRAFT, category: CategoryResponse = CAMISAS) {
    mount(product);
    await flushCategory(category);
  }

  afterEach(() => http.verify());

  it('monta título, descrição, categoria e os atributos do produto pelo tipo do atributo efetivo', async () => {
    await open();

    expect(field<HTMLInputElement>('title').value).toBe('Camiseta Basic');
    expect(field<HTMLTextAreaElement>('description').value).toBe('Malha leve');
    expect(text()).toContain('Vestuário › Camisas');
    const material = field<HTMLSelectElement>('attributes.material');
    expect(material.tagName).toBe('SELECT');
    expect(Array.from(material.options).map(option => option.textContent?.trim())).toEqual(['Escolha', 'Algodão', 'Poliéster']);
    expect(material.value).toBe('Algodão');
    expect(field<HTMLInputElement>('attributes.gramatura').type).toBe('number');
    expect(field<HTMLInputElement>('attributes.gramatura').value).toBe('180');
    expect(field<HTMLInputElement>('attributes.organico').type).toBe('checkbox');
    expect(field<HTMLInputElement>('attributes.gola').type).toBe('text');
    expect(field('attributes.tamanho')).toBeNull();
    expect(element().querySelector('label[for="product-field-attributes.material"]')?.textContent).toContain('*');
    expect(tab.leaveWarning()).toBeNull();
    expect(button(element(), 'Salvar').disabled).toBe(true);
  });

  it('confere o rascunho no checklist ao mudar atributos e grava com o If-Match da versão lida', async () => {
    await open();
    const success = vi.spyOn(TestBed.inject(ToastService), 'success');

    type(field<HTMLInputElement>('title'), 'Camiseta Basic 2');
    choose(field<HTMLSelectElement>('attributes.material'), 'Poliéster');
    field<HTMLInputElement>('attributes.organico').click();
    render();

    expect(store.draft()).toEqual({
      categoryId: 'c1',
      attributes: { material: 'Poliéster', gola: 'Redonda', gramatura: '180', organico: 'true' }
    });
    expect(tab.leaveWarning()).toBe(UNSAVED_CHANGES_WARNING);

    submit();
    const request = await vi.waitFor(() => http.expectOne(`${API}/products/p1`));
    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.get('If-Match')).toBe('"3"');
    expect(request.request.body).toEqual({
      categoryId: 'c1',
      title: 'Camiseta Basic 2',
      description: 'Malha leve',
      attributes: { material: 'Poliéster', gola: 'Redonda', gramatura: '180', organico: 'true' }
    });
    request.flush(
      { ...DRAFT, version: 4, title: 'Camiseta Basic 2', attributes: request.request.body.attributes },
      { headers: { ETag: '"4"' } }
    );

    await vi.waitFor(() => expect(store.version()).toBe('"4"'));
    render();
    expect(success).toHaveBeenCalledWith('Conteúdo salvo.');
    expect(store.draft()).toBeUndefined();
    expect(tab.leaveWarning()).toBeNull();
  });

  it('não grava enquanto os atributos da categoria carregam ou falham, para não apagar os atributos do produto', async () => {
    mount();

    type(field<HTMLInputElement>('title'), 'Camiseta Basic 2');
    expect(tab.changed()).toBe(true);
    expect(button(form(), 'Salvar').disabled).toBe(true);
    submit();
    void tab.write();
    http.expectNone(req => req.method === 'PUT');

    (await vi.waitFor(() => http.expectOne(`${API}/categories/c1`))).flush(
      { type: 'https://marketplace.dev/problems/unavailable', title: 'Indisponível', status: 503 },
      { status: 503, statusText: 'Service Unavailable' }
    );
    await vi.waitFor(() => {
      render();
      expect(text()).toContain('Não foi possível carregar os atributos da categoria.');
    });
    expect(button(form(), 'Salvar').disabled).toBe(true);
    submit();
    http.expectNone(req => req.method === 'PUT');

    button(form(), 'Tentar de novo').click();
    await flushCategory(CAMISAS);
    expect(button(form(), 'Salvar').disabled).toBe(false);
    submit();
    const request = await vi.waitFor(() => http.expectOne(`${API}/products/p1`));
    expect(request.request.body.attributes).toEqual({ material: 'Algodão', gola: 'Redonda', gramatura: '180' });
    request.flush({ ...DRAFT, version: 4, title: 'Camiseta Basic 2' }, { headers: { ETag: '"4"' } });
    await vi.waitFor(() => expect(store.version()).toBe('"4"'));
  });

  it('confirma antes de salvar quando o servidor avisa que salvar devolve o produto à moderação', async () => {
    await open({ ...DRAFT, status: 'ACTIVE', saveReturnsToModeration: true });

    type(field<HTMLInputElement>('title'), 'Camiseta Nova');
    submit();
    const modal = element().querySelector('app-confirm-modal') as HTMLElement;
    expect(modal.textContent).toContain('Salvar devolve o produto à moderação, e ele sai da vitrine até ser aprovado.');
    http.expectNone(`${API}/products/p1`);

    button(modal.querySelector('c-modal-footer') as HTMLElement, 'Salvar').click();
    const request = await vi.waitFor(() => http.expectOne(`${API}/products/p1`));
    request.flush({ ...DRAFT, status: 'PENDING_MODERATION', version: 4, title: 'Camiseta Nova' }, { headers: { ETag: '"4"' } });
    await vi.waitFor(() => expect(store.product()?.status).toBe('PENDING_MODERATION'));
  });

  it('fica só leitura quando o servidor não oferece EDIT', async () => {
    await open({
      ...DRAFT,
      status: 'BLOCKED',
      actions: ['ARCHIVE'],
      blockedActions: [{ action: 'EDIT', reason: { code: 'product-blocked', message: 'Produto bloqueado pela moderação.' } }]
    });

    expect(field<HTMLInputElement>('title').disabled).toBe(true);
    expect(field<HTMLSelectElement>('attributes.material').disabled).toBe(true);
    expect(button(form(), 'Salvar')).toBeUndefined();
    expect(button(form(), 'Trocar')).toBeUndefined();
    expect(text()).toContain('Conteúdo só leitura. Produto bloqueado pela moderação.');
  });

  it('põe os erros do 400 embaixo dos campos e as mensagens do 422 no alerta, sem o código cru', async () => {
    await open();

    type(field<HTMLInputElement>('title'), 'X');
    submit();
    (await vi.waitFor(() => http.expectOne(`${API}/products/p1`))).flush(
      {
        type: 'https://marketplace.dev/problems/validation',
        title: 'Requisição inválida',
        status: 400,
        errors: [{ field: 'title', message: 'O título é curto demais.' }]
      },
      { status: 400, statusText: 'Bad Request' }
    );
    await vi.waitFor(() => {
      render();
      expect(field<HTMLInputElement>('title').parentElement?.textContent).toContain('O título é curto demais.');
    });

    type(field<HTMLInputElement>('title'), 'Camiseta válida');
    submit();
    (await vi.waitFor(() => http.expectOne(`${API}/products/p1`))).flush(
      {
        type: 'https://marketplace.dev/problems/invalid-attributes',
        title: 'Atributos inválidos',
        status: 422,
        reasons: ['invalid-attribute:material'],
        reasonDetails: [{ code: 'invalid-attribute', params: ['material'], message: 'Material não aceita esse valor.' }]
      },
      { status: 422, statusText: 'Unprocessable Content' }
    );
    await vi.waitFor(() => {
      render();
      expect(element().querySelector('app-problem-alert')?.textContent).toContain('Material não aceita esse valor.');
    });
    expect(text()).not.toContain('invalid-attribute');
  });

  it('mantém o digitado no conflito de edição até carregar a versão atual', async () => {
    await open();

    type(field<HTMLInputElement>('title'), 'Meu título');
    submit();
    (await vi.waitFor(() => http.expectOne(`${API}/products/p1`))).flush(
      { type: 'https://marketplace.dev/problems/version-mismatch', title: 'Versão divergente', status: 412 },
      { status: 412, statusText: 'Precondition Failed' }
    );
    await vi.waitFor(() => {
      render();
      expect(text()).toContain('Outra pessoa alterou este registro');
    });
    expect(field<HTMLInputElement>('title').value).toBe('Meu título');

    button(element(), 'Carregar versão atual').click();
    const reload = await vi.waitFor(() => http.expectOne(req => req.method === 'GET' && req.url === `${API}/products/p1`));
    reload.flush({ ...DRAFT, version: 7, title: 'Título de outra pessoa' }, { headers: { ETag: '"7"' } });
    await vi.waitFor(() => {
      render();
      expect(field<HTMLInputElement>('title').value).toBe('Título de outra pessoa');
    });
    expect(text()).not.toContain('Outra pessoa alterou');
    expect(store.version()).toBe('"7"');
  });

  it('troca a categoria só no formulário, com a prévia do servidor, e desfaz', async () => {
    await open();

    button(form(), 'Trocar').click();
    const tree = await treeRequest();
    expect(new URL(tree.request.urlWithParams, 'http://localhost').searchParams.get('usable')).toBe('true');
    tree.flush({
      items: [
        { id: 'c0', name: 'Vestuário', ancestors: [], usable: true },
        { id: 'c1', name: 'Camisas', ancestors: [{ id: 'c0', name: 'Vestuário' }], usable: true },
        { id: 'c2', name: 'Calças', ancestors: [{ id: 'c0', name: 'Vestuário' }], usable: true }
      ]
    });
    const modal = () => element().querySelector('app-category-change-modal') as HTMLElement;
    const modalText = () => modal().textContent?.replace(/\s+/g, ' ') ?? '';
    const select = await vi.waitFor(() => {
      render();
      const found = modal().querySelector('select') as HTMLSelectElement;
      expect(found).toBeTruthy();
      return found;
    });
    expect(Array.from(select.options).map(option => option.textContent?.trim())).toEqual([
      'Escolha a nova categoria',
      'Vestuário',
      'Vestuário › Calças'
    ]);

    choose(select, 'c2');
    const check = await vi.waitFor(() => http.expectOne(`${API}/products/p1/check`));
    expect(check.request.body).toEqual({
      categoryId: 'c2',
      attributes: { material: 'Algodão', gola: 'Redonda', gramatura: '180' }
    });
    check.flush({
      publishingIssues: [],
      categoryChange: {
        removedAttributes: [
          { code: 'gola', label: 'Gola', value: 'Redonda', reason: { code: 'unknown-attribute', message: 'Calças não tem Gola.' } },
          { code: 'gramatura', label: 'Gramatura', value: '180', reason: { code: 'unknown-attribute', message: 'Calças não tem Gramatura.' } }
        ],
        newRequired: [{ code: 'cintura', label: 'Cintura (cm)', variant: false }],
        blockingSkus: []
      }
    });
    await vi.waitFor(() => {
      render();
      expect(modalText()).toContain('Gola (Redonda) deixa de existir.');
    });
    expect(modalText()).toContain('Cintura (cm), obrigatório no produto');
    expect(modalText()).not.toContain('unknown-attribute');

    button(modal().querySelector('c-modal-footer') as HTMLElement, 'Trocar').click();
    render();
    http.expectNone(req => req.method === 'PUT');
    await flushCategory(CALCAS);
    expect(text()).toContain('Categoria trocada de Vestuário › Camisas para Vestuário › Calças');
    expect(field<HTMLSelectElement>('attributes.material').value).toBe('Algodão');
    expect(field('attributes.gola')).toBeNull();
    expect(field<HTMLInputElement>('attributes.cintura').value).toBe('');
    expect(tab.leaveWarning()).toBe(UNSAVED_CHANGES_WARNING);
    expect(store.draft()).toEqual({ categoryId: 'c2', attributes: { material: 'Algodão' } });

    button(element(), 'Desfazer').click();
    await flushCategory(CAMISAS);
    expect(field<HTMLInputElement>('attributes.gola').value).toBe('Redonda');
    expect(text()).not.toContain('Categoria trocada');
    expect(tab.leaveWarning()).toBeNull();
  });

  it('impede a troca com SKUs incompatíveis e orienta conforme o servidor libera remover SKU', async () => {
    await open();

    button(form(), 'Trocar').click();
    (await treeRequest()).flush({
      items: [{ id: 'c2', name: 'Calças', ancestors: [], usable: true }]
    });
    const modal = () => element().querySelector('app-category-change-modal') as HTMLElement;
    const modalText = () => modal().textContent?.replace(/\s+/g, ' ') ?? '';
    const select = await vi.waitFor(() => {
      render();
      const found = modal().querySelector('select') as HTMLSelectElement;
      expect(found).toBeTruthy();
      return found;
    });
    choose(select, 'c2');
    (await vi.waitFor(() => http.expectOne(`${API}/products/p1/check`))).flush({
      categoryChange: {
        blockingSkus: [
          { sellerSku: 'CAM-P-AZ', reasons: [{ code: 'unknown-attribute', message: 'Calças não tem Tamanho como variação.' }] }
        ]
      }
    });
    await vi.waitFor(() => {
      render();
      expect(modalText()).toContain('CAM-P-AZ: Calças não tem Tamanho como variação.');
    });

    expect(modalText()).toContain('Remova esses SKUs, troque a categoria e crie os SKUs de novo.');
    expect(button(modal().querySelector('c-modal-footer') as HTMLElement, 'Trocar').disabled).toBe(true);
  });
});
