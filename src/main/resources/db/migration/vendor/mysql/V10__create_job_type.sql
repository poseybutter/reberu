CREATE TABLE job_type (
    id CHAR(36) NOT NULL,
    org_id CHAR(36) NOT NULL,
    name VARCHAR(40) NOT NULL,
    description VARCHAR(200) NULL,
    hidden TINYINT(1) NOT NULL,
    defaults MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX job_type_org_id_idx ON job_type (org_id);
