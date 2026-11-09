/**
 * Partes do caminho de um campo do 400 (`errors[].field`), para achar o controle do formulário: `skus[0].price` vira
 * `['skus', '0', 'price']`, e `attributes[material].<map value>` vira `['attributes', 'material']`.
 */
export function fieldPath(field: string): string[] {
  return field
    .replace(/<[^>]*>/g, '')
    .replace(/\[([^\]]*)\]/g, '.$1')
    .split('.')
    .filter(Boolean);
}
