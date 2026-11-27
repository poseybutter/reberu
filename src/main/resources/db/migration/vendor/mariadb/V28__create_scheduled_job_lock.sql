CREATE TABLE scheduled_job_lock (
    job_name VARCHAR(60) NOT NULL,
    locked_until DATETIME(6) NOT NULL,
    locked_by CHAR(36) NOT NULL,
    PRIMARY KEY (job_name)
);
