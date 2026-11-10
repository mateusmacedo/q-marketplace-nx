import { downloadTemplate, TEMPLATE_FILE_NAME, templateCsv } from './import-template';

describe('modelo CSV da importação', () => {
  const { createObjectURL, revokeObjectURL } = URL;

  afterEach(() => {
    URL.createObjectURL = createObjectURL;
    URL.revokeObjectURL = revokeObjectURL;
    vi.restoreAllMocks();
  });

  it('tem só o cabeçalho exato que o servidor aceita, com quebra CRLF', () => {
    expect(templateCsv()).toBe(
      'product_ref,category_code,title,description,attributes,seller_sku,variant_attributes,physical,height_cm,' +
      'width_cm,length_cm,weight_g,currency,list_price,sale_price,on_hand\r\n'
    );
  });

  it('baixa o modelo pelo navegador com o nome do arquivo e libera a URL depois do clique', async () => {
    const blobs: Blob[] = [];
    URL.createObjectURL = vi.fn((blob: Blob) => {
      blobs.push(blob);
      return 'blob:modelo';
    });
    const revoke = vi.fn();
    URL.revokeObjectURL = revoke;
    const clicked: HTMLAnchorElement[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push(this);
    });

    downloadTemplate(document);

    expect(revoke).not.toHaveBeenCalled();
    expect(clicked).toHaveLength(1);
    expect(clicked[0].download).toBe(TEMPLATE_FILE_NAME);
    expect(clicked[0].href).toBe('blob:modelo');
    expect(clicked[0].isConnected).toBe(false);
    expect(blobs[0].type).toBe('text/csv;charset=utf-8');
    expect(await readText(blobs[0])).toBe(templateCsv());
    await new Promise(resolve => setTimeout(resolve));
    expect(revoke).toHaveBeenCalledWith('blob:modelo');
  });
});

function readText(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsText(blob);
  });
}
