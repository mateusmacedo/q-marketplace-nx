import { sellerStatusLook, storeEffects } from './store-situation';

describe('situação da loja', () => {
  it('guarda só o rótulo e a cor de cada status', () => {
    expect(sellerStatusLook('APPROVED')).toEqual({ label: 'Aprovada', color: 'success' });
    expect(sellerStatusLook('PENDING')).toEqual({ label: 'Em análise', color: 'warning' });
    expect(sellerStatusLook('SUSPENDED')).toEqual({ label: 'SUSPENDED', color: 'secondary' });
  });

  it('descreve o efeito pelos campos que o servidor calcula, sem olhar o status', () => {
    expect(storeEffects({ canPublish: true, visibleInStorefront: true })).toEqual([
      'Produtos aprovados vão para a vitrine, e os pausados podem ser reativados.',
      'Os produtos ativos aparecem na vitrine.',
    ]);
    expect(storeEffects({ canPublish: false, visibleInStorefront: false })).toEqual([
      'Você pode cadastrar produtos, mas eles só são aprovados e reativados depois da aprovação no KYB/KYC.',
      'Os produtos não aparecem na vitrine.',
    ]);
    expect(storeEffects({})).toHaveLength(2);
  });
});
