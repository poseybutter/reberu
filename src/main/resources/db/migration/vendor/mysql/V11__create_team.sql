CREATE TABLE team (
    id CHAR(36) NOT NULL,
    org_id CHAR(36) NOT NULL,
    name VARCHAR(40) NOT NULL,
    name_key VARCHAR(40) NOT NULL,
    visibility VARCHAR(40) NOT NULL,
    join_mode VARCHAR(40) NOT NULL,
    state VARCHAR(40) NOT NULL,
    job_type_id CHAR(36) NULL,
    report_week_start INT NOT NULL,
    report_lock_weekday INT NULL,
    report_lock_time TIME NULL,
    report_notice_titles VARCHAR(400) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT team_org_id_name_key_key UNIQUE (org_id, name_key)
);

CREATE INDEX team_org_id_state_idx ON team (org_id, state);
