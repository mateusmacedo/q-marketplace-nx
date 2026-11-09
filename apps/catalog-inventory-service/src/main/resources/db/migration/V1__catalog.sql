-- Catálogo: árvore de categorias, produtos do seller com SKUs, histórico de publicação, motivos de moderação e situação
-- KYB/KYC da loja.
-- WHY: o migrator não tem CREATE no banco; fora dos Dev Services, o provisionamento cria a extensão no schema antes
-- (infra/postgres/provision.sh), e aqui ela só é conferida.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE category (
    id         uuid         PRIMARY KEY,
    tenant_id  uuid         NOT NULL,
    parent_id  uuid,
    ancestry   uuid[]       NOT NULL,
    code       varchar(60)  COLLATE "C" NOT NULL,
    name       varchar(120) NOT NULL,
    attributes jsonb        NOT NULL,
    active     boolean      NOT NULL,
    version    bigint       NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT category_tenant_code_uk UNIQUE (tenant_id, code),
    CONSTRAINT category_tenant_id_uk UNIQUE (tenant_id, id),
    -- WHY: a exclusão só vale para a categoria vazia; as chaves estrangeiras recusam o filho ou o produto gravado depois
    -- da leitura, e com o tenant na chave a conferência usa os índices por tenant.
    CONSTRAINT category_parent_fk FOREIGN KEY (tenant_id, parent_id) REFERENCES category (tenant_id, id),
    CONSTRAINT category_code_format CHECK (code ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT category_depth CHECK (cardinality(ancestry) < 6),
    CONSTRAINT category_attributes_array CHECK (jsonb_typeof(attributes) = 'array')
);
CREATE INDEX category_children_idx ON category (tenant_id, parent_id, code);
CREATE INDEX category_ancestry_idx ON category USING gin (ancestry);

-- OUT_OF_STOCK não é gravado: deriva do saldo na leitura (ADR 012).
CREATE TABLE product (
    id          uuid          PRIMARY KEY,
    tenant_id   uuid          NOT NULL,
    store_id    uuid          NOT NULL,
    category_id uuid          NOT NULL,
    currency    char(3)       NOT NULL,
    title       varchar(200)  NOT NULL,
    -- WHY: título em minúsculas, sem acentos e com espaços simples (ProductText), para a busca q e a ordem por título
    -- sem diferenciar maiúsculas e acentos; o COLLATE "C" dá a mesma ordem de cursor em qualquer ambiente.
    title_key   text          COLLATE "C" NOT NULL,
    description varchar(5000) NOT NULL,
    attributes  jsonb         NOT NULL,
    status      varchar(30)   NOT NULL,
    version     bigint        NOT NULL,
    created_at  timestamptz   NOT NULL,
    updated_at  timestamptz   NOT NULL,
    -- WHY: entrada na fila de moderação, que sai em ordem de chegada; o updated_at muda a cada edição feita na fila.
    moderation_queued_at timestamptz,
    CONSTRAINT product_category_fk FOREIGN KEY (tenant_id, category_id) REFERENCES category (tenant_id, id),
    CONSTRAINT product_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT product_status CHECK (status IN ('DRAFT', 'PENDING_MODERATION', 'ACTIVE', 'PAUSED_BY_SELLER', 'BLOCKED',
                                                'ARCHIVED')),
    CONSTRAINT product_moderation_queued CHECK ((status = 'PENDING_MODERATION') = (moderation_queued_at IS NOT NULL)),
    CONSTRAINT product_attributes_object CHECK (jsonb_typeof(attributes) = 'object')
);
-- WHY: a listagem pagina por (coluna da ordem, id) no tenant ou na loja; o estado entra antes da coluna só na ordem
-- padrão (updated_at), e nas outras ordens é filtrado na varredura, para não multiplicar índices a cada gravação.
CREATE INDEX product_updated_idx ON product (tenant_id, updated_at, id);
CREATE INDEX product_status_updated_idx ON product (tenant_id, status, updated_at, id);
CREATE INDEX product_store_updated_idx ON product (tenant_id, store_id, updated_at, id);
CREATE INDEX product_store_status_updated_idx ON product (tenant_id, store_id, status, updated_at, id);
CREATE INDEX product_created_idx ON product (tenant_id, created_at, id);
CREATE INDEX product_store_created_idx ON product (tenant_id, store_id, created_at, id);
CREATE INDEX product_title_idx ON product (tenant_id, title_key, id);
CREATE INDEX product_store_title_idx ON product (tenant_id, store_id, title_key, id);
CREATE INDEX product_title_trgm_idx ON product USING gin (title_key gin_trgm_ops);
CREATE INDEX product_category_idx ON product (tenant_id, category_id);
-- Fila de moderação, em ordem de chegada; parcial, porque só a fila lê a coluna.
CREATE INDEX product_moderation_queue_idx ON product (tenant_id, moderation_queued_at, id)
    WHERE status = 'PENDING_MODERATION';

CREATE TABLE sku (
    id                 uuid        PRIMARY KEY,
    product_id         uuid        NOT NULL REFERENCES product (id),
    tenant_id          uuid        NOT NULL,
    store_id           uuid        NOT NULL,
    position           integer     NOT NULL,
    seller_sku         varchar(64) NOT NULL,
    variant_attributes jsonb       NOT NULL,
    physical           boolean     NOT NULL,
    height_cm          numeric(6, 2),
    width_cm           numeric(6, 2),
    length_cm          numeric(6, 2),
    weight_g           integer,
    CONSTRAINT sku_store_seller_sku_uk UNIQUE (tenant_id, store_id, seller_sku),
    CONSTRAINT sku_seller_sku_format CHECK (seller_sku ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    -- Invariante do PRD: SKU físico sempre tem dimensões e peso.
    CONSTRAINT sku_dimensions CHECK (
        (physical AND height_cm > 0 AND width_cm > 0 AND length_cm > 0 AND weight_g > 0)
        OR (NOT physical AND height_cm IS NULL AND width_cm IS NULL AND length_cm IS NULL AND weight_g IS NULL))
);
CREATE INDEX sku_product_idx ON sku (product_id, position);
CREATE INDEX sku_seller_sku_trgm_idx ON sku USING gin (lower(seller_sku) gin_trgm_ops);

CREATE TABLE product_status_change (
    id          uuid         PRIMARY KEY,
    product_id  uuid         NOT NULL REFERENCES product (id),
    tenant_id   uuid         NOT NULL,
    -- WHY: cópia da loja do produto, que não muda, para as séries da loja lerem só o índice dela.
    store_id    uuid         NOT NULL,
    transition  varchar(30)  NOT NULL,
    from_status varchar(30)  NOT NULL,
    to_status   varchar(30)  NOT NULL,
    actor       varchar(255) NOT NULL,
    -- Na rejeição e no bloqueio: os códigos dos motivos prontos, a cópia dos rótulos da época ([{code, label}]), para
    -- renomear ou desativar um motivo não mudar o passado, e o complemento; o texto único é derivado na leitura. Vazios
    -- e nulo nas demais transições.
    reason_codes  text[]      NOT NULL,
    reason_labels jsonb       NOT NULL,
    note          varchar(500),
    occurred_at timestamptz  NOT NULL,
    CONSTRAINT product_status_change_labels CHECK (jsonb_typeof(reason_labels) = 'array'
        AND jsonb_array_length(reason_labels) = cardinality(reason_codes))
);
CREATE INDEX product_status_change_idx ON product_status_change (product_id, occurred_at, id);
-- Séries da página inicial, contadas só no índice: as transições da loja (seller) e, no tenant, as que a moderação lê,
-- de entrada e saída da fila e de bloqueio; a criação, a pausa e a reativação ficam fora, e o índice fica menor.
CREATE INDEX product_status_change_store_idx ON product_status_change (tenant_id, store_id, occurred_at)
    INCLUDE (transition, from_status, to_status);
CREATE INDEX product_status_change_moderation_idx ON product_status_change (tenant_id, occurred_at)
    INCLUDE (transition, from_status, to_status)
    WHERE to_status = 'PENDING_MODERATION' OR from_status = 'PENDING_MODERATION' OR transition = 'BLOCK';

-- Motivos prontos de rejeição e bloqueio, um catálogo por tenant, mantido pelo catalog-admin. Sem tabela de tenant não
-- há semente: o tenant novo começa sem motivos e decide só com o complemento. Não há exclusão, porque o código fica no
-- histórico e nos eventos; desativar tira o motivo das decisões novas.
CREATE TABLE moderation_reason (
    tenant_id         uuid         NOT NULL,
    code              varchar(60)  COLLATE "C" NOT NULL,
    label             varchar(120) NOT NULL,
    applies_to_reject boolean      NOT NULL,
    applies_to_block  boolean      NOT NULL,
    position          integer      NOT NULL,
    active            boolean      NOT NULL,
    version           bigint       NOT NULL,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    CONSTRAINT moderation_reason_tenant_code_pk PRIMARY KEY (tenant_id, code),
    CONSTRAINT moderation_reason_code_format CHECK (code ~ '^[a-z]+(-[a-z]+)*$'),
    CONSTRAINT moderation_reason_decision CHECK (applies_to_reject OR applies_to_block),
    CONSTRAINT moderation_reason_position CHECK (position BETWEEN 0 AND 9999)
);

-- Cópia local do veredito KYB/KYC; decided_at ordena vereditos que chegam fora de ordem (ADR 013).
CREATE TABLE seller_eligibility (
    tenant_id  uuid        NOT NULL,
    store_id   uuid        NOT NULL,
    status     varchar(20) NOT NULL,
    decided_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, store_id),
    CONSTRAINT seller_eligibility_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);
