-- Importação de produtos por planilha (ADR 016): o lote guarda o CSV enviado e o processador grava o resultado por linha.
-- started_at é a última reivindicação ou progresso; um lote em PROCESSING parado há mais que o lease volta a ser reivindicável.
CREATE TABLE product_import (
    id          uuid         PRIMARY KEY,
    tenant_id   uuid         NOT NULL,
    store_id    uuid         NOT NULL,
    actor       varchar(255) NOT NULL,
    status      varchar(20)  NOT NULL,
    content     text         NOT NULL,
    total_lines integer      NOT NULL,
    created_at  timestamptz  NOT NULL,
    started_at  timestamptz,
    finished_at timestamptz,
    CONSTRAINT product_import_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED')),
    CONSTRAINT product_import_total_lines CHECK (total_lines > 0),
    CONSTRAINT product_import_started CHECK ((status = 'PENDING') = (started_at IS NULL)),
    CONSTRAINT product_import_finished CHECK ((status = 'COMPLETED') = (finished_at IS NOT NULL))
);
CREATE INDEX product_import_pending_idx ON product_import (created_at, id) WHERE status IN ('PENDING', 'PROCESSING');

CREATE TABLE product_import_line (
    import_id   uuid          NOT NULL REFERENCES product_import (id) ON DELETE CASCADE,
    line_number integer       NOT NULL,
    seller_sku  varchar(100),
    outcome     varchar(20)   NOT NULL,
    code        varchar(100),
    message     varchar(1000),
    product_id  uuid,
    PRIMARY KEY (import_id, line_number),
    CONSTRAINT product_import_line_number CHECK (line_number > 0),
    CONSTRAINT product_import_line_outcome CHECK (outcome IN ('CREATED', 'UPDATED', 'UNCHANGED', 'FAILED')),
    -- Só a linha recusada tem código e mensagem; a bem-sucedida sempre aponta o produto.
    CONSTRAINT product_import_line_failure CHECK (
        (outcome = 'FAILED' AND code IS NOT NULL AND message IS NOT NULL)
        OR (outcome <> 'FAILED' AND code IS NULL AND message IS NULL AND product_id IS NOT NULL))
);
