/**
 * Partes do caminho de um campo do 400 (`errors[].field`), para achar o controle do formulário: `skus[0].price` vira
 * `['skus', '0', 'price']`, e `attributes[material].<map value>` vira `['attributes', 'material']`.
 */
export function fieldPath(field: string): string[] {
  const withoutNodes = replaceBetween(field, '<', '>', () => '');
  return replaceBetween(withoutNodes, '[', ']', (key) => `.${key}`)
    .split('.')
    .filter(Boolean);
}

/**
 * Troca cada trecho de `open` até o primeiro `close` seguinte por `replace(miolo)`; um `open` sem `close` fica como
 * está.
 */
function replaceBetween(
  text: string,
  open: string,
  close: string,
  replace: (inner: string) => string,
): string {
  // WHY: as expressões regulares equivalentes (`<[^>]*>` e `\[([^\]]*)\]`) refazem a varredura a cada delimitador
  // sem par, em tempo quadrático; com `indexOf` o texto é lido uma só vez.
  let result = '';
  let from = 0;
  for (let start = text.indexOf(open); start !== -1; start = text.indexOf(open, from)) {
    const end = text.indexOf(close, start + 1);
    if (end === -1) {
      break;
    }
    result += text.slice(from, start) + replace(text.slice(start + 1, end));
    from = end + 1;
  }
  return result + text.slice(from);
}
