// WHY: cópia pendente (ADR 031); saída: GET /products/imports/template (text/csv).
/** Cabeçalho do CSV que `POST /products/imports` aceita, exato e nesta ordem (ADR 016). */
export const TEMPLATE_COLUMNS = [
  'product_ref',
  'category_code',
  'title',
  'description',
  'attributes',
  'seller_sku',
  'variant_attributes',
  'physical',
  'height_cm',
  'width_cm',
  'length_cm',
  'weight_g',
  'currency',
  'list_price',
  'sale_price',
  'on_hand',
] as const;

export const TEMPLATE_FILE_NAME = 'modelo-importacao-produtos.csv';

/** Modelo CSV: só o cabeçalho, com a quebra de linha CRLF da RFC 4180. */
export function templateCsv(): string {
  return `${TEMPLATE_COLUMNS.join(',')}\r\n`;
}

/** Baixa o modelo CSV pelo navegador, sem chamar a API. */
export function downloadTemplate(document: Document): void {
  const url = URL.createObjectURL(new Blob([templateCsv()], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = TEMPLATE_FILE_NAME;
  link.hidden = true;
  document.body.append(link);
  link.click();
  link.remove();
  // WHY: revogar a URL no mesmo ciclo do clique pode cancelar o download em alguns navegadores.
  setTimeout(() => URL.revokeObjectURL(url));
}
