CREATE SEQUENCE post_search_outbox_events_seq
    START WITH 1
    INCREMENT BY 1;

CREATE TABLE post_search_outbox_events
(
    outbox_event_id BIGINT        NOT NULL,
    aggregate_id    BIGINT        NOT NULL,
    event_type      VARCHAR(20)   NOT NULL,
    payload_version INTEGER       NOT NULL,
    payload         TEXT          NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    attempt_count   INTEGER       NOT NULL DEFAULT 0,
    available_at    TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    created_at      TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    claimed_at      TIMESTAMP(6) WITHOUT TIME ZONE,
    claimed_by      VARCHAR(100),
    processed_at    TIMESTAMP(6) WITHOUT TIME ZONE,
    last_error      VARCHAR(2000),
    row_version     BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_post_search_outbox_events PRIMARY KEY (outbox_event_id),
    CONSTRAINT ck_post_search_outbox_event_type
        CHECK (event_type IN ('UPSERT', 'DELETE')),
    CONSTRAINT ck_post_search_outbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED')),
    CONSTRAINT ck_post_search_outbox_payload_version
        CHECK (payload_version > 0),
    CONSTRAINT ck_post_search_outbox_attempt_count
        CHECK (attempt_count >= 0)
);

CREATE INDEX idx_post_search_outbox_pending
    ON post_search_outbox_events (status, available_at, outbox_event_id);

CREATE INDEX idx_post_search_outbox_aggregate_order
    ON post_search_outbox_events (aggregate_id, outbox_event_id);
