ALTER TABLE job_type ADD COLUMN name_key VARCHAR(40) NULL;
UPDATE job_type SET name_key = LOWER(name);
ALTER TABLE job_type ALTER COLUMN name_key SET NOT NULL;
ALTER TABLE job_type ADD CONSTRAINT job_type_org_id_name_key_key UNIQUE (org_id, name_key);
