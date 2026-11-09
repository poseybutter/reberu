CREATE TABLE api_idempotency_key (
    account_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(36) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_status INT NULL,
    response_body MEDIUMTEXT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (account_id, idempotency_key)
);

CREATE INDEX api_idempotency_key_expires_at_idx ON api_idempotency_key (expires_at);
