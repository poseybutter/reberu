CREATE TRIGGER org_activity_log_no_update BEFORE UPDATE ON org_activity_log
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'activity log is append-only';

CREATE TRIGGER team_activity_log_no_update BEFORE UPDATE ON team_activity_log
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'activity log is append-only';
