CREATE TABLE team_task_label (
    id CHAR(36) NOT NULL,
    team_id CHAR(36) NOT NULL,
    name VARCHAR(30) NOT NULL,
    name_key VARCHAR(30) NOT NULL,
    sort_order INTEGER NOT NULL,
    hidden BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version INTEGER NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT team_task_label_team_id_name_key_key UNIQUE (team_id, name_key)
);
