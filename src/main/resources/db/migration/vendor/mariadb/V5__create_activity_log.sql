CREATE TABLE org_activity_log (
    id CHAR(36) NOT NULL,
    org_id CHAR(36) NOT NULL,
    actor_id CHAR(36) NULL,
    action VARCHAR(40) NOT NULL,
    target_type VARCHAR(40) NOT NULL,
    target_id CHAR(36) NOT NULL,
    before_value VARCHAR(2000) NULL,
    after_value VARCHAR(2000) NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX org_activity_log_org_id_occurred_at_idx ON org_activity_log (org_id, occurred_at);
CREATE INDEX org_activity_log_org_id_actor_id_idx ON org_activity_log (org_id, actor_id);
CREATE INDEX org_activity_log_occurred_at_idx ON org_activity_log (occurred_at);

CREATE TABLE team_activity_log (
    id CHAR(36) NOT NULL,
    team_id CHAR(36) NOT NULL,
    actor_id CHAR(36) NULL,
    action VARCHAR(40) NOT NULL,
    target_type VARCHAR(40) NOT NULL,
    target_id CHAR(36) NOT NULL,
    before_value VARCHAR(2000) NULL,
    after_value VARCHAR(2000) NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX team_activity_log_team_id_occurred_at_idx ON team_activity_log (team_id, occurred_at);
CREATE INDEX team_activity_log_team_id_actor_id_idx ON team_activity_log (team_id, actor_id);
CREATE INDEX team_activity_log_occurred_at_idx ON team_activity_log (occurred_at);
