CREATE TABLE auth_session (
    session_id_hash CHAR(64) NOT NULL,
    account_id CHAR(36) NULL,
    authenticated_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    last_used_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    login_state_hash CHAR(64) NULL,
    login_nonce VARCHAR(64) NULL,
    login_code_verifier VARCHAR(128) NULL,
    login_return_path VARCHAR(200) NULL,
    login_started_at TIMESTAMP(6) NULL,
    PRIMARY KEY (session_id_hash)
);

CREATE INDEX auth_session_account_id_idx ON auth_session (account_id);
CREATE INDEX auth_session_expires_at_idx ON auth_session (expires_at);
