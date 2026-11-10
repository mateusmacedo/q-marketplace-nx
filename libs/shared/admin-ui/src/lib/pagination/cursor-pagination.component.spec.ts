import { ComponentFixture, TestBed } from '@angular/core/testing';

import { CursorPaginationComponent } from './cursor-pagination.component';

describe('CursorPaginationComponent', () => {
  let fixture: ComponentFixture<CursorPaginationComponent>;
  let component: CursorPaginationComponent;
  let requested: (string | undefined)[];

  const buttons = () =>
    Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
  const previousButton = () =>
    buttons().find((button) => button.textContent?.trim() === 'Anterior') as HTMLButtonElement;
  const nextButton = () =>
    buttons().find((button) => button.textContent?.trim() === 'Próxima') as HTMLButtonElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CursorPaginationComponent],
    }).compileComponents();
    fixture = TestBed.createComponent(CursorPaginationComponent);
    component = fixture.componentInstance;
    requested = [];
    component.cursorChange.subscribe((cursor) => requested.push(cursor));
    fixture.componentRef.setInput('nextCursor', 'c2');
    await fixture.whenStable();
  });

  it('desabilita Anterior na primeira página e Próxima sem próximo cursor', async () => {
    expect(previousButton().disabled).toBe(true);
    expect(nextButton().disabled).toBe(false);

    fixture.componentRef.setInput('nextCursor', null);
    await fixture.whenStable();
    expect(nextButton().disabled).toBe(true);
  });

  it('avança pelo próximo cursor e volta pela pilha', async () => {
    nextButton().click();
    fixture.componentRef.setInput('cursor', 'c2');
    fixture.componentRef.setInput('nextCursor', 'c3');
    await fixture.whenStable();
    nextButton().click();
    fixture.componentRef.setInput('cursor', 'c3');
    await fixture.whenStable();

    expect(previousButton().disabled).toBe(false);
    previousButton().click();
    fixture.componentRef.setInput('cursor', 'c2');
    await fixture.whenStable();
    previousButton().click();

    expect(requested).toEqual(['c2', 'c3', 'c2', undefined]);
  });

  it('volta à primeira página quando a lista zera o cursor, como ao trocar o filtro', async () => {
    nextButton().click();
    fixture.componentRef.setInput('cursor', 'c2');
    await fixture.whenStable();
    expect(component.hasPrevious()).toBe(true);

    fixture.componentRef.setInput('cursor', undefined);
    await fixture.whenStable();
    expect(component.hasPrevious()).toBe(false);
  });

  it('oferece 20, 50 e 100 itens e emite o tamanho escolhido', async () => {
    const sizes: number[] = [];
    component.limitChange.subscribe((size) => sizes.push(size));
    fixture.componentRef.setInput('limit', '50');
    await fixture.whenStable();

    const select: HTMLSelectElement = fixture.nativeElement.querySelector('select');
    expect(Array.from(select.options).map((option) => option.value)).toEqual(['20', '50', '100']);
    expect(select.value).toBe('50');

    select.value = '100';
    select.dispatchEvent(new Event('change'));
    expect(sizes).toEqual([100]);
  });

  it('trava os controles durante o carregamento', async () => {
    fixture.componentRef.setInput('disabled', true);
    await fixture.whenStable();
    expect(nextButton().disabled).toBe(true);
    expect((fixture.nativeElement.querySelector('select') as HTMLSelectElement).disabled).toBe(
      true,
    );
  });
});
