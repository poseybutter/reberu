CREATE TABLE invite_link (
    id CHAR(36) NOT NULL,
    org_id CHAR(36) NOT NULL,
    team_id CHAR(36) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    approval_required BOOLEAN NOT NULL,
    role VARCHAR(40) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    max_uses INTEGER NOT NULL,
    use_count INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_by CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version INTEGER NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT invite_link_token_hash_key UNIQUE (token_hash)
);

CREATE INDEX invite_link_team_id_created_at_idx ON invite_link (team_id, created_at);
CREATE INDEX invite_link_org_id_enabled_idx ON invite_link (org_id, enabled);
