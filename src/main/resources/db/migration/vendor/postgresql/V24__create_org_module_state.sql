CREATE TABLE org_module_state (
    id CHAR(36) NOT NULL,
    org_id CHAR(36) NOT NULL,
    module_key VARCHAR(40) NOT NULL,
    allowed BOOLEAN NOT NULL,
    enabled BOOLEAN NOT NULL,
    changed_by CHAR(36),
    changed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT org_module_state_org_id_module_key_key UNIQUE (org_id, module_key)
);
