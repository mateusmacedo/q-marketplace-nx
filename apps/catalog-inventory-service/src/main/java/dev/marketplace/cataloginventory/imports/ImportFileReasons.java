package dev.marketplace.cataloginventory.imports;

import dev.marketplace.cataloginventory.domain.ReasonCode;

/**
 * Registro dos motivos com que a validação estrutural recusa o arquivo de
 * importação. Código novo nasce aqui e ganha texto no catálogo de mensagens.
 */
public enum ImportFileReasons implements ReasonCode {
  EMPTY_FILE, INVALID_ENCODING, MALFORMED_CSV, NO_ROWS, TOO_MANY_ROWS, DUPLICATE_COLUMN, UNKNOWN_COLUMN, MISSING_COLUMN, COLUMN_ORDER
}
