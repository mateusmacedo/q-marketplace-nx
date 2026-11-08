-- Ofertas: preço de/por e promoções de cada SKU, com versão própria, separadas do conteúdo do produto (ADR 014).
CREATE TABLE offer (
    sku_id        uuid           PRIMARY KEY REFERENCES sku (id) ON DELETE CASCADE,
    product_id    uuid           NOT NULL REFERENCES product (id),
    tenant_id     uuid           NOT NULL,
    store_id      uuid           NOT NULL,
    currency      char(3)        NOT NULL,
    list_price    numeric(19, 2),
    sale_price    numeric(19, 2) NOT NULL,
    price_version bigint         NOT NULL,
    version       bigint         NOT NULL,
    created_at    timestamptz    NOT NULL,
    updated_at    timestamptz    NOT NULL,
    CONSTRAINT offer_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT offer_prices CHECK (sale_price >= 0 AND (list_price IS NULL OR list_price >= sale_price))
);
CREATE INDEX offer_product_idx ON offer (tenant_id, product_id);

CREATE TABLE offer_promotion (
    id         uuid           PRIMARY KEY,
    sku_id     uuid           NOT NULL REFERENCES offer (sku_id) ON DELETE CASCADE,
    sale_price numeric(19, 2) NOT NULL,
    starts_at  timestamptz    NOT NULL,
    ends_at    timestamptz    NOT NULL,
    CONSTRAINT offer_promotion_price CHECK (sale_price >= 0),
    CONSTRAINT offer_promotion_window CHECK (starts_at < ends_at)
);
CREATE INDEX offer_promotion_sku_idx ON offer_promotion (sku_id, starts_at);
