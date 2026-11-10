describe('estilos globais do painel', () => {
  it('prende na rolagem da tabela o texto oculto dos cabeçalhos, sem alargar a página no celular', () => {
    const wrapper = document.createElement('div');
    wrapper.className = 'table-responsive';
    wrapper.style.width = '200px';
    wrapper.innerHTML =
      '<table class="table" style="width: 600px"><thead><tr><th>Produto</th>' +
      '<th style="width: 400px"><span class="visually-hidden">Ações</span></th></tr></thead></table>';
    document.body.append(wrapper);

    try {
      expect(getComputedStyle(wrapper).position).toBe('relative');
      expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(
        document.documentElement.clientWidth,
      );
    } finally {
      wrapper.remove();
    }
  });
});
