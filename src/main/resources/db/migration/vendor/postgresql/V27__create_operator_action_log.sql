CREATE TABLE operator_action_log (
    id CHAR(36) NOT NULL,
    operator_account_id CHAR(36) NOT NULL,
    action VARCHAR(40) NOT NULL,
    org_id CHAR(36) NOT NULL,
    reason VARCHAR(200),
    occurred_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX operator_action_log_org_id_occurred_at_idx ON operator_action_log (org_id, occurred_at);
