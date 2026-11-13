CREATE TABLE invitation (
    id CHAR(36) NOT NULL,
    org_id CHAR(36) NOT NULL,
    team_id CHAR(36) NULL,
    email VARCHAR(254) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    role VARCHAR(40) NULL,
    guest_days INTEGER NULL,
    state VARCHAR(40) NOT NULL,
    accept_by TIMESTAMP(6) NOT NULL,
    created_by CHAR(36) NULL,
    approved_by CHAR(36) NULL,
    accepted_by CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version INTEGER NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX invitation_email_state_idx ON invitation (email, state);
CREATE INDEX invitation_org_id_email_idx ON invitation (org_id, email);
CREATE INDEX invitation_org_id_state_idx ON invitation (org_id, state);
CREATE INDEX invitation_team_id_state_idx ON invitation (team_id, state);
