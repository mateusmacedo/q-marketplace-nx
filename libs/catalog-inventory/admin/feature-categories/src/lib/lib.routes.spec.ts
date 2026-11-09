import { leaveGuard } from '@marketplace/shared-admin-ui';

import { CategoriesPageComponent } from './categories-page.component';
import { CategoryEvents } from './category-events';
import { CategoryPageComponent } from './category-page.component';
import { routes } from './lib.routes';

describe('rotas da feature', () => {
  it('carrega a lista e o detalhe sob demanda, com o título de cada rota e o aviso de saída', async () => {
    const [list] = routes;
    const [detail] = list.children ?? [];

    expect(await list.loadComponent?.()).toBe(CategoriesPageComponent);
    expect(list.data?.['title']).toBe('Categorias');
    expect(list.canDeactivate).toEqual([leaveGuard]);
    expect(list.providers).toEqual([CategoryEvents]);

    expect(detail.path).toBe(':id');
    expect(await detail.loadComponent?.()).toBe(CategoryPageComponent);
    expect(detail.data?.['title']).toBe('Categoria');
    expect(detail.canDeactivate).toEqual([leaveGuard]);
  });
});
