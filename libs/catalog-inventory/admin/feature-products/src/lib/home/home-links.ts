/**
 * Endereços das telas que os atalhos da página inicial abrem. O app monta este objeto só com as rotas que a sessão
 * libera; sem o endereço, o atalho some.
 */
export interface HomeLinks {
  readonly storeProducts?: string;
  readonly tenantProducts?: string;
  readonly moderationQueue?: string;
  readonly categories?: string;
}

/** Lista que abre um produto: a da loja, quando a sessão tem loja, ou a de todas as lojas. */
export function productsLink(links: HomeLinks): string | undefined {
  return links.storeProducts ?? links.tenantProducts;
}
