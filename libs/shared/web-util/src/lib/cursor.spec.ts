import { back, canGoBack, followCursor, forward, startTrail, toPageSize } from './cursor';

describe('pilha de cursores', () => {
  it('começa na primeira página sem como voltar', () => {
    const trail = startTrail();
    expect(trail).toEqual({ current: undefined, previous: [] });
    expect(canGoBack(trail)).toBe(false);
  });

  it('avança e volta pelos cursores visitados', () => {
    const second = forward(startTrail(), 'c2');
    const third = forward(second, 'c3');
    expect(third).toEqual({ current: 'c3', previous: [undefined, 'c2'] });
    expect(back(third)).toEqual(second);
    expect(back(back(third))).toEqual(startTrail());
    expect(back(startTrail())).toEqual(startTrail());
  });

  it('volta para a primeira página quando a página veio da URL sem histórico', () => {
    const trail = startTrail('c7');
    expect(canGoBack(trail)).toBe(true);
    expect(back(trail)).toEqual({ current: undefined, previous: [] });
  });

  it('acompanha o cursor da URL', () => {
    const third = forward(forward(startTrail(), 'c2'), 'c3');
    expect(followCursor(third, 'c3')).toBe(third);
    expect(followCursor(third, 'c2')).toEqual({ current: 'c2', previous: [undefined] });
    expect(followCursor(third, undefined)).toEqual(startTrail());
    expect(followCursor(third, '')).toEqual(startTrail());
    expect(followCursor(third, 'c4')).toEqual({ current: 'c4', previous: [undefined, 'c2', 'c3'] });
  });

  it('aceita só 20, 50 e 100 itens por página', () => {
    expect(toPageSize('50')).toBe(50);
    expect(toPageSize(100)).toBe(100);
    expect(toPageSize('30')).toBe(20);
    expect(toPageSize(undefined)).toBe(20);
  });
});
