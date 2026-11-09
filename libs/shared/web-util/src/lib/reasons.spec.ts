import { GENERIC_REASON_MESSAGE, parseReason, translateReason } from './reasons';

describe('texto dos motivos', () => {
  it('usa a mensagem do servidor', () => {
    expect(translateReason({ code: 'missing-offer', params: ['CAM-P'], message: 'Defina o preço do SKU CAM-P.' })).toBe(
      'Defina o preço do SKU CAM-P.'
    );
  });

  it('cai no detail e depois no title do problema quando o motivo não traz mensagem', () => {
    const problem = { detail: 'Descrição tem até 5000 caracteres', title: 'Valor inválido' };
    expect(translateReason({ code: 'invalid-value' }, problem)).toBe('Descrição tem até 5000 caracteres');
    expect(translateReason({ code: 'invalid-value', message: '  ' }, { title: 'Valor inválido' })).toBe('Valor inválido');
  });

  it('nunca mostra o código cru, nem do motivo compacto', () => {
    expect(translateReason({ code: 'batch-too-large' })).toBe(GENERIC_REASON_MESSAGE);
    expect(translateReason('missing-variant-attribute:CAM-P:cor')).toBe(GENERIC_REASON_MESSAGE);
    expect(translateReason({})).toBe(GENERIC_REASON_MESSAGE);
  });

  it('dá texto aos problemas que o próprio front cria, sem resposta do servidor', () => {
    expect(translateReason({ code: 'network-error' })).toBe(
      'Não foi possível falar com o servidor. Confira a conexão e tente de novo.'
    );
    expect(translateReason({ code: 'http-502' })).toBe('O servidor respondeu com o erro HTTP 502.');
  });

  it('usa o texto do servidor para os motivos de moderação recusados, sem sobrescrita do front', () => {
    const reason = {
      code: 'unknown-reason',
      params: ['imagem-impropria'],
      message: 'Um dos motivos escolhidos não está no catálogo de motivos. Recarregue a lista de motivos.'
    };
    expect(translateReason(reason)).toBe(reason.message);
  });

  it('separa o motivo compacto em código e parâmetros', () => {
    expect(parseReason('missing-variant-attribute:CAM-P:cor')).toEqual({
      code: 'missing-variant-attribute',
      params: ['CAM-P', 'cor']
    });
    expect(parseReason('no-sku')).toEqual({ code: 'no-sku', params: [] });
  });
});
