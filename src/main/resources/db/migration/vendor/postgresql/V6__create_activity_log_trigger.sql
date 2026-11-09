CREATE FUNCTION activity_log_reject_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'activity log is append-only';
END;
$$;

CREATE TRIGGER org_activity_log_no_update BEFORE UPDATE ON org_activity_log
    FOR EACH ROW EXECUTE FUNCTION activity_log_reject_change();
CREATE TRIGGER org_activity_log_no_truncate BEFORE TRUNCATE ON org_activity_log
    FOR EACH STATEMENT EXECUTE FUNCTION activity_log_reject_change();
CREATE TRIGGER team_activity_log_no_update BEFORE UPDATE ON team_activity_log
    FOR EACH ROW EXECUTE FUNCTION activity_log_reject_change();
CREATE TRIGGER team_activity_log_no_truncate BEFORE TRUNCATE ON team_activity_log
    FOR EACH STATEMENT EXECUTE FUNCTION activity_log_reject_change();
