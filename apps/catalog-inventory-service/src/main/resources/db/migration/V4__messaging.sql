-- Mensageria: outbox transacional publicada pelo relay e registro de entregas consumidas (ADR 013).
-- seq cresce na ordem de gravação; dentro de um agregado, os bloqueios de linha fazem essa ordem seguir a de commit.
CREATE TABLE
    outbox_event (
        seq bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        id uuid NOT NULL,
        tenant_id uuid NOT NULL,
        topic text NOT NULL CHECK (char_length(topic) <= 200),
        message_key text NOT NULL CHECK (char_length(message_key) <= 100),
        event_type text NOT NULL CHECK (char_length(event_type) <= 60),
        -- json (não jsonb) preserva o texto serializado, que é publicado byte a byte.
        payload json NOT NULL,
        -- traceparent W3C da requisição que gravou o evento; nulo com o OpenTelemetry desligado.
        trace_parent text CHECK (char_length(trace_parent) <= 55),
        created_at timestamptz NOT NULL,
        published_at timestamptz,
        attempts integer NOT NULL DEFAULT 0,
        last_error text CHECK (char_length(last_error) <= 500),
        CONSTRAINT outbox_event_id_uk UNIQUE (id)
    );

CREATE INDEX outbox_event_pending_idx ON outbox_event (seq)
WHERE
    published_at IS NULL;

CREATE INDEX outbox_event_published_idx ON outbox_event (published_at)
WHERE
    published_at IS NOT NULL;

CREATE TABLE
    inbox_message (
        consumer text NOT NULL CHECK (char_length(consumer) <= 100),
        event_id uuid NOT NULL,
        processed_at timestamptz NOT NULL,
        PRIMARY KEY (consumer, event_id)
    );

CREATE INDEX inbox_message_processed_idx ON inbox_message (processed_at);