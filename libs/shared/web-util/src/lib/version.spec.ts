import { apiDataWithVersion, responseVersion, versionTag } from './version';

/** Cabeçalhos com o `get` sem diferenciar maiúsculas, como o `HttpHeaders` do Angular e o `Headers` do fetch. */
function headers(values: Record<string, string>) {
  const map = new Map(Object.entries(values).map(([name, value]) => [name.toLowerCase(), value]));
  return { get: (name: string) => map.get(name.toLowerCase()) ?? null };
}

describe('versão do registro (ETag)', () => {
  it('devolve os dados e o ETag da resposta do cliente gerado', async () => {
    const result = { data: { id: 'p1', title: 'Camiseta' }, request: {}, response: { headers: headers({ ETag: '"7"' }) } };
    await expect(apiDataWithVersion(Promise.resolve(result))).resolves.toEqual({
      data: { id: 'p1', title: 'Camiseta' },
      version: '"7"'
    });
  });

  it('prefere a version do corpo ao ETag, que um proxy com gzip enfraquece', async () => {
    const result = { data: { id: 'p1', version: 2 }, response: { headers: headers({ ETag: 'W/"2"' }) } };
    await expect(apiDataWithVersion(Promise.resolve(result))).resolves.toEqual({ data: { id: 'p1', version: 2 }, version: '"2"' });
  });

  it('sem version no corpo, usa o ETag forte e ignora o fraco', async () => {
    const strong = { data: { id: 's1' }, response: { headers: headers({ ETag: '"5"' }) } };
    const weak = { data: { id: 's1' }, response: { headers: headers({ ETag: 'W/"5"' }) } };
    await expect(apiDataWithVersion(Promise.resolve(strong))).resolves.toEqual({ data: { id: 's1' }, version: '"5"' });
    await expect(apiDataWithVersion(Promise.resolve(weak))).resolves.toEqual({ data: { id: 's1' }, version: undefined });
  });

  it('deixa a versão ausente quando a resposta não traz o ETag', async () => {
    const result = { data: { id: 'p1' }, response: { headers: headers({}) } };
    await expect(apiDataWithVersion(Promise.resolve(result))).resolves.toEqual({ data: { id: 'p1' }, version: undefined });
  });

  it('lança o problema normalizado na falha, como apiData', async () => {
    const response = { status: 412, error: { type: 'https://marketplace.dev/problems/version-mismatch', title: 'Versão' } };
    await expect(apiDataWithVersion(Promise.resolve({ error: response.error, response }))).rejects.toEqual({
      type: 'https://marketplace.dev/problems/version-mismatch',
      title: 'Versão',
      status: 412
    });
  });

  it('lê o ETag da resposta ou direto dos cabeçalhos de um httpResource', () => {
    expect(responseVersion({ headers: headers({ etag: ' "3" ' }) })).toBe('"3"');
    expect(responseVersion(headers({ ETag: '"4"' }))).toBe('"4"');
    expect(responseVersion(undefined)).toBeUndefined();
    expect(responseVersion({ headers: { ETag: '"5"' } })).toBeUndefined();
    expect(responseVersion(headers({ ETag: 'W/"6"' }))).toBeUndefined();
  });

  it('monta o ETag da versão que veio no corpo', () => {
    expect(versionTag(3)).toBe('"3"');
    expect(versionTag('12')).toBe('"12"');
    expect(versionTag(0)).toBe('"0"');
    expect(versionTag(null)).toBeUndefined();
    expect(versionTag(undefined)).toBeUndefined();
  });
});
