CREATE TABLE organization (
    id CHAR(36) NOT NULL,
    name VARCHAR(40) NOT NULL,
    tagline VARCHAR(100) NULL,
    visibility VARCHAR(40) NOT NULL,
    state VARCHAR(40) NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    join_link_enabled TINYINT(1) NOT NULL,
    member_admission_review TINYINT(1) NOT NULL,
    join_request_mode VARCHAR(40) NOT NULL,
    guest_approval_required TINYINT(1) NOT NULL,
    guest_default_days INT NOT NULL,
    guest_max_days INT NOT NULL,
    guest_orgspace_allowed TINYINT(1) NOT NULL,
    invitation_ttl_days INT NOT NULL,
    ownerless TINYINT(1) NOT NULL,
    created_by CHAR(36) NULL,
    state_before_suspend VARCHAR(40) NULL,
    deletion_scheduled_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version INT NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX organization_visibility_state_name_idx ON organization (visibility, state, name);
CREATE INDEX organization_created_by_idx ON organization (created_by);
