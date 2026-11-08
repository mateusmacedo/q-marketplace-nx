-- Nova tentativa por evento na outbox (ADR 013): a falha adia só o próprio evento (next_attempt_at) e, esgotadas as
-- tentativas, o estaciona (parked_at) até o reenvio manual, em vez de pausar o relay inteiro.
ALTER TABLE outbox_event
    ADD COLUMN next_attempt_at timestamptz,
    ADD COLUMN parked_at timestamptz;

DROP INDEX outbox_event_pending_idx;

CREATE INDEX outbox_event_pending_idx ON outbox_event (seq)
WHERE
    published_at IS NULL AND parked_at IS NULL;

-- Procura, por chave, um anterior pendente em espera ou estacionado, que retém os seguintes para manter a ordem.
CREATE INDEX outbox_event_key_pending_idx ON outbox_event (topic, message_key, seq)
WHERE
    published_at IS NULL;
