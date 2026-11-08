-- Catálogo: árvore de categorias, produtos do seller com SKUs, histórico de publicação e situação KYB/KYC da loja.
CREATE TABLE category (
    id         uuid         PRIMARY KEY,
    tenant_id  uuid         NOT NULL,
    parent_id  uuid         REFERENCES category (id),
    ancestry   uuid[]       NOT NULL,
    code       varchar(60)  COLLATE "C" NOT NULL,
    name       varchar(120) NOT NULL,
    attributes jsonb        NOT NULL,
    active     boolean      NOT NULL,
    version    bigint       NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT category_tenant_code_uk UNIQUE (tenant_id, code),
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
    category_id uuid          NOT NULL REFERENCES category (id),
    currency    char(3)       NOT NULL,
    title       varchar(200)  NOT NULL,
    description varchar(5000) NOT NULL,
    attributes  jsonb         NOT NULL,
    status      varchar(30)   NOT NULL,
    version     bigint        NOT NULL,
    created_at  timestamptz   NOT NULL,
    updated_at  timestamptz   NOT NULL,
    CONSTRAINT product_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT product_status CHECK (status IN ('DRAFT', 'PENDING_MODERATION', 'ACTIVE', 'PAUSED_BY_SELLER', 'BLOCKED',
                                                'ARCHIVED')),
    CONSTRAINT product_attributes_object CHECK (jsonb_typeof(attributes) = 'object')
);
CREATE INDEX product_tenant_idx ON product (tenant_id, id);
CREATE INDEX product_store_idx ON product (tenant_id, store_id, id);
CREATE INDEX product_status_idx ON product (tenant_id, status, id);

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

CREATE TABLE product_status_change (
    id          uuid         PRIMARY KEY,
    product_id  uuid         NOT NULL REFERENCES product (id),
    tenant_id   uuid         NOT NULL,
    transition  varchar(30)  NOT NULL,
    from_status varchar(30)  NOT NULL,
    to_status   varchar(30)  NOT NULL,
    actor       varchar(255) NOT NULL,
    reason      varchar(500),
    occurred_at timestamptz  NOT NULL
);
CREATE INDEX product_status_change_idx ON product_status_change (product_id, occurred_at, id);

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
