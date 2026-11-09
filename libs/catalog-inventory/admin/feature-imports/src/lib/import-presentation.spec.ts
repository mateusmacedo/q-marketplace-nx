import { GENERIC_REASON_MESSAGE } from '@marketplace/shared-web-util';

import {
  countLines,
  isImportRunning,
  lineTexts,
  locationId,
  outcomePresentation,
  progressPercent,
  statusPresentation
} from './import-presentation';

describe('apresentação da importação', () => {
  it('dá rótulo e cor à situação do lote e mostra o valor cru do status que não conhece', () => {
    expect(statusPresentation('PROCESSING')).toEqual({ label: 'Processando', color: 'info' });
    expect(statusPresentation('COMPLETED')).toEqual({ label: 'Concluída', color: 'success' });
    expect(statusPresentation('CANCELLED')).toEqual({ label: 'CANCELLED', color: 'secondary' });
    expect(statusPresentation('constructor')).toEqual({ label: 'constructor', color: 'secondary' });
  });

  it('dá rótulo e cor ao resultado da linha', () => {
    expect(outcomePresentation('CREATED').label).toBe('Criada');
    expect(outcomePresentation('UNCHANGED').label).toBe('Sem mudança');
    expect(outcomePresentation('FAILED')).toEqual({ label: 'Falha', color: 'danger' });
    expect(outcomePresentation(undefined)).toEqual({ label: 'Desconhecido', color: 'secondary' });
  });

  it('consulta o lote só em PENDING e PROCESSING; qualquer outro status é terminal', () => {
    expect(isImportRunning('PENDING')).toBe(true);
    expect(isImportRunning('PROCESSING')).toBe(true);
    expect(isImportRunning('COMPLETED')).toBe(false);
    expect(isImportRunning('FAILED')).toBe(false);
    expect(isImportRunning(undefined)).toBe(false);
  });

  it('mostra a mensagem da linha e os motivos do servidor sem repetir texto nem mostrar o código', () => {
    const texts = lineTexts({
      lineNumber: 14,
      outcome: 'FAILED',
      code: 'invalid-product',
      message: 'Produto inválido',
      reasonDetails: [
        { code: 'missing-variant-attribute', params: ['CAM-P', 'cor'], message: 'O SKU CAM-P não tem o atributo Cor' },
        { code: 'invalid-product', message: 'Produto inválido' },
        { code: 'missing-offer' }
      ]
    });

    expect(texts.message).toBe('Produto inválido');
    expect(texts.reasons).toEqual(['O SKU CAM-P não tem o atributo Cor']);
    expect(JSON.stringify(texts)).not.toContain('missing-');
  });

  it('mostra a mensagem genérica na falha sem texto e nada na linha que deu certo', () => {
    expect(lineTexts({ lineNumber: 3, outcome: 'FAILED', code: 'invalid-row' })).toEqual({
      message: GENERIC_REASON_MESSAGE,
      reasons: []
    });
    expect(lineTexts({ lineNumber: 2, outcome: 'CREATED', productId: 'p1' })).toEqual({ message: undefined, reasons: [] });
  });

  it('lê o id do lote no Location da resposta 202', () => {
    expect(locationId('http://catalogo:8080/products/imports/3f2a-1')).toBe('3f2a-1');
    expect(locationId('/api/catalog-inventory/products/imports/3f2a-1/?x=1')).toBe('3f2a-1');
    expect(locationId(null)).toBeUndefined();
    expect(locationId('')).toBeUndefined();
  });

  it('calcula o andamento em porcentagem inteira, sem marcar 100 antes da última linha', () => {
    expect(progressPercent(300, 1200)).toBe(25);
    expect(progressPercent(1199, 1200)).toBe(99);
    expect(progressPercent(1200, 1200)).toBe(100);
    expect(progressPercent(undefined, 1200)).toBe(0);
    expect(progressPercent(5, 0)).toBe(0);
  });

  it('conta as linhas no singular e no plural', () => {
    expect(countLines(1)).toBe('1 linha');
    expect(countLines(1200)).toBe('1200 linhas');
  });
});
