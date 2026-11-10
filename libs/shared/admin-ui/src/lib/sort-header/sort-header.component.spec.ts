import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SortHeaderComponent } from './sort-header.component';

@Component({
  imports: [SortHeaderComponent],
  template: `
    <table>
      <thead>
        <tr>
          <th (sortChange)="sort.set($event)" [sort]="sort()" app-sort-header="title">Produto</th>
          <th (sortChange)="sort.set($event)" [sort]="sort()" app-sort-header="updatedAt" firstDirection="desc">Atualizado</th>
        </tr>
      </thead>
    </table>
  `,
})
class TableHost {
  readonly sort = signal('-updatedAt');
}

describe('SortHeaderComponent', () => {
  let fixture: ComponentFixture<TableHost>;

  const headers = () => Array.from(fixture.nativeElement.querySelectorAll('th')) as HTMLElement[];
  const click = async (index: number) => {
    (headers()[index].querySelector('button') as HTMLButtonElement).click();
    await fixture.whenStable();
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [TableHost] }).compileComponents();
    fixture = TestBed.createComponent(TableHost);
    await fixture.whenStable();
  });

  it('marca o aria-sort só na coluna ativa', () => {
    expect(headers().map((header) => header.getAttribute('aria-sort'))).toEqual([
      'none',
      'descending',
    ]);
  });

  it('inverte a coluna ativa', async () => {
    await click(1);
    expect(fixture.componentInstance.sort()).toBe('updatedAt');
    expect(headers()[1].getAttribute('aria-sort')).toBe('ascending');
  });

  it('começa outra coluna pela ordem padrão dela', async () => {
    await click(0);
    expect(fixture.componentInstance.sort()).toBe('title');
    expect(headers().map((header) => header.getAttribute('aria-sort'))).toEqual([
      'ascending',
      'none',
    ]);
  });

  it('mantém o texto da coluna no botão', () => {
    expect(headers()[0].querySelector('button')?.textContent?.trim()).toBe('Produto');
  });
});
