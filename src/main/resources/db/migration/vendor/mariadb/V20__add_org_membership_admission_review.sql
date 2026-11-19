ALTER TABLE org_membership ADD COLUMN admission_review TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE org_membership ALTER COLUMN admission_review DROP DEFAULT;
