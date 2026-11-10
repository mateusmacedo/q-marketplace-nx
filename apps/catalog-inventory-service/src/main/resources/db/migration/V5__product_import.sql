-- Importação de produtos por planilha (ADR 016): o lote guarda o CSV enviado e o processador grava o resultado por linha.
-- started_at é a última reivindicação ou progresso; um lote em PROCESSING parado há mais que o lease volta a ser reivindicável.
CREATE TABLE product_import (
    id          uuid         PRIMARY KEY,
    tenant_id   uuid         NOT NULL,
    store_id    uuid         NOT NULL,
    actor       text         NOT NULL CHECK (char_length(actor) <= 255),
    status      text         NOT NULL,
    content     text         NOT NULL,
    total_lines integer      NOT NULL,
    created_at  timestamptz  NOT NULL,
    started_at  timestamptz,
    finished_at timestamptz,
    -- Contagens por desfecho das linhas já gravadas, somadas junto com elas, para a lista de lotes não ler as linhas.
    created_lines   integer NOT NULL DEFAULT 0,
    updated_lines   integer NOT NULL DEFAULT 0,
    unchanged_lines integer NOT NULL DEFAULT 0,
    failed_lines    integer NOT NULL DEFAULT 0,
    CONSTRAINT product_import_status CHECK (CASE status
        WHEN 'PENDING' THEN started_at IS NULL AND finished_at IS NULL
        WHEN 'PROCESSING' THEN started_at IS NOT NULL AND finished_at IS NULL
        WHEN 'COMPLETED' THEN started_at IS NOT NULL AND finished_at IS NOT NULL
        ELSE false
    END),
    CONSTRAINT product_import_total_lines CHECK (total_lines > 0)
);
CREATE INDEX product_import_pending_idx ON product_import (created_at, id) WHERE status IN ('PENDING', 'PROCESSING');
-- Lista de lotes da loja, do mais recente ao mais antigo.
CREATE INDEX product_import_store_idx ON product_import (tenant_id, store_id, created_at, id);

CREATE TABLE product_import_line (
    import_id   uuid          NOT NULL REFERENCES product_import (id) ON DELETE CASCADE,
    line_number integer       NOT NULL,
    seller_sku  text          CHECK (char_length(seller_sku) <= 100),
    outcome     text          NOT NULL,
    code        text          CHECK (char_length(code) <= 100),
    message     text          CHECK (char_length(message) <= 1000),
    -- Motivos da recusa de regra de negócio, [{code, params, message}], no formato do corpo de erro da API.
    reasons     jsonb,
    product_id  uuid,
    PRIMARY KEY (import_id, line_number),
    CONSTRAINT product_import_line_number CHECK (line_number > 0),
    -- Só a linha recusada tem código, mensagem e motivos; a bem-sucedida sempre aponta o produto.
    CONSTRAINT product_import_line_outcome CHECK (CASE
        WHEN outcome = 'FAILED' THEN code IS NOT NULL AND message IS NOT NULL
        WHEN outcome IN ('CREATED', 'UPDATED', 'UNCHANGED')
            THEN code IS NULL AND message IS NULL AND reasons IS NULL AND product_id IS NOT NULL
        ELSE false
    END)
);
