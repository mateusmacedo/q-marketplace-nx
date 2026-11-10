/** Abas do produto, na ordem da tela: na loja, cada uma é uma rota filha; em todas as lojas, uma aba do template. */
export const PRODUCT_TABS = [
  { path: 'conteudo', label: 'Conteúdo' },
  { path: 'skus', label: 'SKUs, preço e estoque' },
  { path: 'historico', label: 'Histórico' },
] as const;
