CREATE TABLE EVENT_PUBLICATION (
    id VARCHAR(36) NOT NULL,
    listener_id VARCHAR(512) NOT NULL,
    event_type VARCHAR(512) NOT NULL,
    serialized_event VARCHAR(4000) NOT NULL,
    publication_date TIMESTAMP(6) NOT NULL,
    completion_date TIMESTAMP(6) NULL,
    status VARCHAR(20),
    completion_attempts INT,
    last_resubmission_date TIMESTAMP(6) NULL,
    PRIMARY KEY (id)
);

CREATE INDEX event_publication_completion_date_idx ON EVENT_PUBLICATION (completion_date);
