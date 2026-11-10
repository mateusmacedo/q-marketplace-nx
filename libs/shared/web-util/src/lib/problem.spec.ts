import {
  apiData,
  fieldErrors,
  isEditConflict,
  isNetworkError,
  problemCode,
  problemMessages,
  problemReasons,
  toProblem,
} from './problem';
import { GENERIC_REASON_MESSAGE } from './reasons';

const TYPE = 'https://marketplace.dev/problems/';

describe('problema RFC 9457', () => {
  it('usa o corpo do problema como veio', () => {
    const body = {
      type: `${TYPE}no-sku`,
      title: 'Regra de negócio violada',
      status: 422,
      reasons: ['no-sku'],
    };
    expect(toProblem(body)).toEqual(body);
  });

  it('lê o corpo e o status do erro do HttpClient', () => {
    const error = {
      status: 412,
      error: { type: `${TYPE}version-mismatch`, title: 'Versão desatualizada' },
    };
    expect(toProblem(error)).toEqual({
      type: `${TYPE}version-mismatch`,
      title: 'Versão desatualizada',
      status: 412,
    });
  });

  it('transforma resposta sem corpo de problema em http-<status> e falha de rede em network-error', () => {
    expect(toProblem({ status: 502, error: '<html>Bad Gateway</html>' })).toEqual({
      type: 'http-502',
      status: 502,
    });
    expect(toProblem({ status: 0, error: {} })).toEqual({ type: 'network-error', status: 0 });
    expect(toProblem(new Error('boom'))).toEqual({ type: 'network-error', status: 0 });
  });

  it('extrai o código do fim do type', () => {
    expect(problemCode({ type: `${TYPE}invalid-transition`, status: 409 })).toBe(
      'invalid-transition',
    );
    expect(problemCode({ title: 'Sem type', status: 500 })).toBe('http-500');
  });

  it('mostra a mensagem de cada motivo de reasonDetails, sem repetir texto', () => {
    const problem = {
      type: `${TYPE}product-not-publishable`,
      title: 'Regra de negócio violada',
      status: 422,
      reasons: ['no-sku', 'missing-offer:CAM-P', 'missing-offer:CAM-M'],
      reasonDetails: [
        { code: 'no-sku', params: [], message: 'Adicione ao menos um SKU.' },
        { code: 'missing-offer', params: ['CAM-P'], message: 'Defina o preço dos SKUs.' },
        { code: 'missing-offer', params: ['CAM-M'], message: 'Defina o preço dos SKUs.' },
      ],
    };
    expect(problemMessages(problem)).toEqual([
      'Adicione ao menos um SKU.',
      'Defina o preço dos SKUs.',
    ]);
    expect(problemReasons(problem).map((reason) => reason.code)).toEqual([
      'no-sku',
      'missing-offer',
      'missing-offer',
    ]);
  });

  it('cai no detail e depois no title quando o servidor não manda reasonDetails', () => {
    expect(
      problemMessages({
        type: `${TYPE}not-found`,
        status: 404,
        title: 'Não encontrado',
        detail: 'Produto não encontrado',
      }),
    ).toEqual(['Produto não encontrado']);
    expect(
      problemMessages({
        type: `${TYPE}version-mismatch`,
        status: 412,
        title: 'Versão desatualizada',
      }),
    ).toEqual(['Versão desatualizada']);
    const legacy = {
      type: `${TYPE}product-not-publishable`,
      title: 'Regra violada',
      reasons: ['no-sku', 'missing-offer:CAM-P'],
    };
    expect(problemMessages(legacy)).toEqual(['Regra violada']);
    expect(problemReasons(legacy)).toEqual([
      { code: 'no-sku', params: [] },
      { code: 'missing-offer', params: ['CAM-P'] },
    ]);
  });

  it('nunca mostra o código cru do problema', () => {
    expect(problemMessages({ type: `${TYPE}batch-too-large`, status: 400 })).toEqual([
      GENERIC_REASON_MESSAGE,
    ]);
    expect(problemMessages(toProblem(new Error('boom')))).toEqual([
      'Não foi possível falar com o servidor. Confira a conexão e tente de novo.',
    ]);
    expect(problemReasons(null)).toEqual([]);
  });

  it('devolve os dados do cliente gerado ou lança o problema com o status da resposta', async () => {
    await expect(apiData(Promise.resolve({ data: { id: 1 } }))).resolves.toEqual({ id: 1 });
    const body = { type: `${TYPE}not-found`, title: 'Não encontrado' };
    await expect(
      apiData(Promise.resolve({ error: body, response: { status: 404, error: body } })),
    ).rejects.toEqual({
      ...body,
      status: 404,
    });
    await expect(
      apiData(Promise.resolve({ error: null, response: { status: 401, error: null } })),
    ).rejects.toEqual({
      type: 'http-401',
      status: 401,
    });
    await expect(apiData(Promise.resolve({ error: new TypeError('rede') }))).rejects.toEqual({
      type: 'network-error',
      status: 0,
    });
  });

  it('separa a primeira mensagem de cada campo', () => {
    const problem = {
      status: 400,
      errors: [
        { field: 'title', message: 'obrigatório' },
        { field: 'title', message: 'até 200 caracteres' },
        { field: 'currency', message: 'ISO 4217' },
      ],
    };
    expect(fieldErrors(problem)).toEqual({ title: 'obrigatório', currency: 'ISO 4217' });
    expect(fieldErrors(null)).toEqual({});
  });

  it('aceita o erro de campo do contrato, sem campo ou sem mensagem', () => {
    const problem = {
      status: 400,
      errors: [
        { field: 'title' },
        { field: 'title', message: 'obrigatório' },
        { message: 'Confira os dados.' },
        {},
      ],
    };
    expect(fieldErrors(problem)).toEqual({ title: 'obrigatório', '': 'Confira os dados.' });
  });

  it('reconhece o conflito de edição só no 412 e no concurrent-update', () => {
    expect(isEditConflict({ type: `${TYPE}version-mismatch`, status: 412 })).toBe(true);
    expect(isEditConflict({ type: `${TYPE}concurrent-update`, status: 409 })).toBe(true);
    expect(isEditConflict({ type: `${TYPE}invalid-transition`, status: 409 })).toBe(false);
    expect(isEditConflict(undefined)).toBe(false);
  });

  it('reconhece a falha de rede', () => {
    expect(isNetworkError(toProblem({ status: 0, error: {} }))).toBe(true);
    expect(isNetworkError({ type: `${TYPE}not-found`, status: 404 })).toBe(false);
    expect(isNetworkError(null)).toBe(false);
  });
});
