CREATE TABLE org_membership (
    id CHAR(36) NOT NULL,
    org_id CHAR(36) NOT NULL,
    account_id CHAR(36) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    org_admin TINYINT(1) NOT NULL,
    state VARCHAR(40) NOT NULL,
    joined_via VARCHAR(40) NOT NULL,
    expires_at DATETIME(6) NULL,
    left_reason VARCHAR(40) NULL,
    rejected_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT org_membership_org_id_account_id_key UNIQUE (org_id, account_id)
);

CREATE INDEX org_membership_org_id_state_idx ON org_membership (org_id, state);
CREATE INDEX org_membership_account_id_state_idx ON org_membership (account_id, state);
CREATE INDEX org_membership_org_id_org_admin_state_idx ON org_membership (org_id, org_admin, state);
