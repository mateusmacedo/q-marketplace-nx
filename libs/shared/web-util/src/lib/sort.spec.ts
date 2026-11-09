import { formatSort, nextSort, parseSort } from './sort';

describe('ordem das listas', () => {
  it('lê e escreve a ordem da URL', () => {
    expect(parseSort('-updatedAt')).toEqual({ field: 'updatedAt', direction: 'desc' });
    expect(parseSort('title')).toEqual({ field: 'title', direction: 'asc' });
    expect(parseSort('')).toBeNull();
    expect(parseSort(null)).toBeNull();
    expect(formatSort({ field: 'createdAt', direction: 'desc' })).toBe('-createdAt');
    expect(formatSort({ field: 'title', direction: 'asc' })).toBe('title');
  });

  it('inverte a coluna ativa e usa a ordem padrão em outra coluna', () => {
    const current = parseSort('-updatedAt');
    expect(nextSort(current, 'updatedAt', 'desc')).toEqual({ field: 'updatedAt', direction: 'asc' });
    expect(nextSort(current, 'title', 'asc')).toEqual({ field: 'title', direction: 'asc' });
    expect(nextSort(current, 'createdAt', 'desc')).toEqual({ field: 'createdAt', direction: 'desc' });
    expect(nextSort(null, 'title', 'asc')).toEqual({ field: 'title', direction: 'asc' });
  });
});
