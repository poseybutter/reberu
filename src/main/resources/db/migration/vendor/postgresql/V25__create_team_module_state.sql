CREATE TABLE team_module_state (
    id CHAR(36) NOT NULL,
    team_id CHAR(36) NOT NULL,
    module_key VARCHAR(40) NOT NULL,
    enabled BOOLEAN NOT NULL,
    changed_by CHAR(36),
    changed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT team_module_state_team_id_module_key_key UNIQUE (team_id, module_key)
);
