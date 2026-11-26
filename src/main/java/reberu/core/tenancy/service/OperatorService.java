package reberu.core.tenancy.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.audit.ActivityEntry;
import reberu.core.audit.ActivityLogs;
import reberu.core.support.ErrorCode;
import reberu.core.support.FieldReason;
import reberu.core.support.FieldViolation;
import reberu.core.support.ReberuException;
import reberu.core.support.SensitiveText;
import reberu.core.support.Uuids;
import reberu.core.tenancy.entity.Organization;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;
import reberu.core.tenancy.entity.TenancyCodes.OrgState;
import reberu.core.tenancy.repository.OrgMembershipRepository;
import reberu.core.tenancy.repository.OrganizationRepository;

public class OperatorService {

    public static final int REASON_MAX = 200;

    private static final String SUSPENDED = "ORG_SUSPENDED";
    private static final String UNSUSPENDED = "ORG_UNSUSPENDED";

    private final OrganizationRepository organizations;
    private final OrgMembershipRepository orgMemberships;
    private final ActivityLogs activityLogs;
    private final JdbcClient jdbc;
    private final Clock clock;

    OperatorService(OrganizationRepository organizations, OrgMembershipRepository orgMemberships,
            ActivityLogs activityLogs, JdbcClient jdbc, Clock clock) {
        this.organizations = organizations;
        this.orgMemberships = orgMemberships;
        this.activityLogs = activityLogs;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public void suspend(UUID operatorId, UUID orgId, String reason) {
        String checked = checkedReason(reason);
        Organization organization = lock(orgId);
        OrgState before = organization.state();
        if (before == OrgState.SUSPENDED) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        if (before != OrgState.ACTIVE && before != OrgState.ARCHIVED) {
            throw new ReberuException(ErrorCode.READ_ONLY);
        }
        Instant now = clock.instant();
        organization.suspend(now);
        record(operatorId, SUSPENDED, orgId, checked, now);
        activityLogs.recordOrg(orgId,
                new ActivityEntry(null, SUSPENDED, "ORG", orgId, before.name(), OrgState.SUSPENDED.name()));
    }

    @Transactional
    public void unsuspend(UUID operatorId, UUID orgId) {
        Organization organization = lock(orgId);
        if (organization.state() != OrgState.SUSPENDED) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        if (orgMemberships.countByOrgIdAndOrgAdminTrueAndState(orgId, MembershipState.ACTIVE) == 0) {
            throw new ReberuException(ErrorCode.LAST_ADMIN_REQUIRED);
        }
        Instant now = clock.instant();
        organization.unsuspend(now);
        record(operatorId, UNSUSPENDED, orgId, null, now);
        activityLogs.recordOrg(orgId, new ActivityEntry(null, UNSUSPENDED, "ORG", orgId,
                OrgState.SUSPENDED.name(), OrgState.ACTIVE.name()));
    }

    private Organization lock(UUID orgId) {
        return organizations.findForUpdate(orgId)
                .filter(organization -> organization.state() != OrgState.DELETED)
                .orElseThrow(() -> new ReberuException(ErrorCode.NOT_FOUND));
    }

    private void record(UUID operatorId, String action, UUID orgId, String reason, Instant at) {
        jdbc.sql("""
                        INSERT INTO operator_action_log (id, operator_account_id, action, org_id, reason, occurred_at)
                        VALUES (:id, :operator, :action, :org, :reason, :occurredAt)
                        """)
                .param("id", Uuids.v7().toString())
                .param("operator", operatorId.toString())
                .param("action", action)
                .param("org", orgId.toString())
                .param("reason", reason)
                .param("occurredAt", LocalDateTime.ofInstant(at.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC))
                .update();
    }

    private static String checkedReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw invalid(FieldReason.REQUIRED);
        }
        String stripped = reason.strip();
        if (stripped.length() > REASON_MAX) {
            throw invalid(FieldReason.TOO_LONG);
        }
        if (SensitiveText.containsControl(stripped)) {
            throw invalid(FieldReason.INVALID_FORMAT);
        }
        if (SensitiveText.containsSensitive(stripped)) {
            throw invalid(FieldReason.NOT_ALLOWED_VALUE);
        }
        return stripped;
    }

    private static ReberuException invalid(FieldReason reason) {
        return new ReberuException(ErrorCode.INVALID_REQUEST, List.of(new FieldViolation("reason", reason)));
    }

}
