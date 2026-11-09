CREATE TABLE team_membership (
    id CHAR(36) NOT NULL,
    team_id CHAR(36) NOT NULL,
    account_id CHAR(36) NOT NULL,
    role VARCHAR(40) NOT NULL,
    state VARCHAR(40) NOT NULL,
    joined_via VARCHAR(40) NOT NULL,
    expires_at TIMESTAMP(6) NULL,
    left_reason VARCHAR(40) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version INTEGER NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT team_membership_team_id_account_id_key UNIQUE (team_id, account_id)
);

CREATE INDEX team_membership_team_id_state_role_idx ON team_membership (team_id, state, role);
CREATE INDEX team_membership_account_id_state_idx ON team_membership (account_id, state);
