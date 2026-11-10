-- Estoque: saldo por SKU, trilha de ajustes e reservas com prazo no banco (ADR 012).
CREATE TABLE
    inventory_level (
        sku_id uuid PRIMARY KEY,
        product_id uuid NOT NULL REFERENCES product (id),
        tenant_id uuid NOT NULL,
        store_id uuid NOT NULL,
        on_hand integer NOT NULL DEFAULT 0,
        reserved integer NOT NULL DEFAULT 0,
        sold bigint NOT NULL DEFAULT 0,
        version bigint NOT NULL DEFAULT 0,
        updated_at timestamptz NOT NULL DEFAULT now (),
        -- Sem cascata: SKU com estoque ou reserva não pode ser removido.
        CONSTRAINT inventory_level_sku_fk FOREIGN KEY (sku_id) REFERENCES sku (id),
        CONSTRAINT inventory_non_negative CHECK (
            on_hand >= 0
            AND reserved >= 0
            AND sold >= 0
        ),
        CONSTRAINT inventory_reserved_within_on_hand CHECK (reserved <= on_hand)
    );

CREATE INDEX inventory_product_idx ON inventory_level (product_id);

CREATE TABLE
    inventory_adjustment (
        id uuid PRIMARY KEY,
        sku_id uuid NOT NULL,
        tenant_id uuid NOT NULL,
        store_id uuid NOT NULL,
        previous_on_hand integer NOT NULL,
        new_on_hand integer NOT NULL,
        actor text NOT NULL CHECK (char_length(actor) <= 255),
        occurred_at timestamptz NOT NULL
    );

CREATE TABLE
    inventory_reservation (
        id uuid PRIMARY KEY,
        tenant_id uuid NOT NULL,
        idempotency_key text NOT NULL CHECK (char_length(idempotency_key) <= 255),
        request_hash text NOT NULL CHECK (char_length(request_hash) = 64),
        order_ref text CHECK (char_length(order_ref) <= 100),
        status text NOT NULL,
        created_at timestamptz NOT NULL,
        expires_at timestamptz NOT NULL,
        closed_at timestamptz,
        CONSTRAINT reservation_idempotency_uk UNIQUE (tenant_id, idempotency_key),
        CONSTRAINT reservation_status CHECK (
            CASE
                WHEN status = 'ACTIVE' THEN closed_at IS NULL
                WHEN status IN ('COMMITTED', 'RELEASED', 'EXPIRED') THEN closed_at IS NOT NULL
                ELSE false
            END
        ),
        CONSTRAINT reservation_window CHECK (expires_at > created_at)
    );

-- Índice parcial: o varredor só procura reservas ativas vencidas.
CREATE INDEX reservation_due_idx ON inventory_reservation (expires_at)
WHERE
    status = 'ACTIVE';

-- Snapshot do preço da oferta (valor e versão) no momento da reserva.
CREATE TABLE
    inventory_reservation_item (
        reservation_id uuid NOT NULL REFERENCES inventory_reservation (id),
        sku_id uuid NOT NULL,
        product_id uuid NOT NULL,
        store_id uuid NOT NULL,
        quantity integer NOT NULL,
        currency text NOT NULL,
        unit_price numeric(19, 2) NOT NULL,
        price_version bigint NOT NULL,
        PRIMARY KEY (reservation_id, sku_id),
        CONSTRAINT reservation_item_quantity CHECK (quantity > 0),
        CONSTRAINT reservation_item_price CHECK (unit_price >= 0),
        CONSTRAINT reservation_item_currency_format CHECK (currency ~ '^[A-Z]{3}$')
    );
