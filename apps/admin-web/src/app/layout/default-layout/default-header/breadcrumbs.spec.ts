import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, RouterOutlet, type Routes } from '@angular/router';

import { breadcrumbs } from './breadcrumbs';

@Component({ template: '' })
class Screen { }

@Component({ imports: [RouterOutlet], template: '<router-outlet />' })
class Shell { }

const ROUTES: Routes = [
  {
    path: '',
    component: Shell,
    children: [
      { path: 'inicio', component: Screen, data: { title: 'Início' } },
      {
        path: 'loja',
        children: [
          {
            path: 'produtos',
            children: [
              {
                path: '',
                data: { title: 'Produtos' },
                children: [
                  { path: '', component: Screen },
                  {
                    path: ':id',
                    component: Shell,
                    data: { title: 'Produto' },
                    children: [{ path: 'conteudo', component: Screen, data: { title: 'Conteúdo' } }]
                  }
                ]
              }
            ]
          }
        ]
      },
      {
        path: 'categorias',
        component: Shell,
        data: { title: 'Categorias' },
        children: [{ path: ':id', component: Screen, data: { title: 'Categorias' } }]
      }
    ]
  }
];

describe('breadcrumbs', () => {
  async function trail(url: string) {
    TestBed.configureTestingModule({ providers: [provideRouter(ROUTES)] });
    const router = TestBed.inject(Router);
    await router.navigateByUrl(url);
    return breadcrumbs(router.routerState.snapshot.root);
  }

  it('mostra o grupo da lista e o produto antes da aba, cada um com o seu endereço', async () => {
    expect(await trail('/loja/produtos/p1/conteudo')).toEqual([
      { label: 'Produtos', url: '/loja/produtos' },
      { label: 'Produto', url: '/loja/produtos/p1' },
      { label: 'Conteúdo', url: '/loja/produtos/p1/conteudo' }
    ]);
  });

  it('não repete o título que a lista só herda do grupo', async () => {
    expect(await trail('/loja/produtos')).toEqual([{ label: 'Produtos', url: '/loja/produtos' }]);
  });

  it('junta dois títulos iguais seguidos e mostra a tela de um passo só', async () => {
    expect(await trail('/categorias/c1')).toEqual([{ label: 'Categorias', url: '/categorias' }]);
    TestBed.resetTestingModule();
    expect(await trail('/inicio')).toEqual([{ label: 'Início', url: '/inicio' }]);
  });
});
