ALTER TABLE org_membership ADD COLUMN admission_review BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE org_membership ALTER COLUMN admission_review DROP DEFAULT;
