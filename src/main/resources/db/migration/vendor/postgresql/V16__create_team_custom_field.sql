CREATE TABLE team_custom_field (
    id CHAR(36) NOT NULL,
    team_id CHAR(36) NOT NULL,
    target VARCHAR(40) NOT NULL,
    label VARCHAR(40) NOT NULL,
    label_key VARCHAR(40) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    required BOOLEAN NOT NULL,
    sort_order INTEGER NOT NULL,
    hidden BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version INTEGER NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT team_custom_field_team_id_target_label_key_key UNIQUE (team_id, target, label_key)
);
