package reberu.core.tenancy.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import reberu.core.audit.ActivityEntry;
import reberu.core.audit.ActivityLogs;
import reberu.core.tenancy.entity.OrgMembership;
import reberu.core.tenancy.entity.TeamMembership;
import reberu.core.tenancy.entity.TenancyCodes.MembershipKind;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;
import reberu.core.tenancy.repository.OrgMembershipRepository;
import reberu.core.tenancy.repository.OrganizationRepository;
import reberu.core.tenancy.repository.TeamMembershipRepository;

class GuestExpiry {

    private static final Logger log = LoggerFactory.getLogger(GuestExpiry.class);

    private final OrganizationRepository organizations;
    private final OrgMembershipRepository orgMemberships;
    private final TeamMembershipRepository teamMemberships;
    private final ActivityLogs activityLogs;
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final Clock clock;

    GuestExpiry(OrganizationRepository organizations, OrgMembershipRepository orgMemberships,
            TeamMembershipRepository teamMemberships, ActivityLogs activityLogs, TransactionTemplate transactions,
            JdbcClient jdbc, Clock clock) {
        this.organizations = organizations;
        this.orgMemberships = orgMemberships;
        this.teamMemberships = teamMemberships;
        this.activityLogs = activityLogs;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${reberu.tenancy.guest-expiry-interval}",
            fixedDelayString = "${reberu.tenancy.guest-expiry-interval}")
    void scheduled() {
        expireDue();
    }

    int expireDue() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int total = 0;
        for (UUID orgId : dueOrgs(now)) {
            try {
                Integer expired = transactions.execute(status -> expireOrg(orgId, now));
                total += expired == null ? 0 : expired;
            }
            catch (RuntimeException exception) {
                log.error("손님 만료 처리 실패: 조직 {}", orgId, exception);
            }
        }
        if (total > 0) {
            log.info("손님 만료 처리: 멤버십 {}건", total);
        }
        return total;
    }

    private List<UUID> dueOrgs(Instant now) {
        return jdbc.sql("""
                        SELECT t.org_id FROM team_membership tm
                        JOIN team t ON t.id = tm.team_id
                        WHERE tm.state = 'ACTIVE' AND tm.expires_at <= :now
                        UNION
                        SELECT m.org_id FROM org_membership m
                        WHERE m.state = 'ACTIVE' AND m.kind = 'GUEST'
                          AND (m.expires_at <= :now OR NOT EXISTS (
                            SELECT 1 FROM team_membership tm
                            JOIN team t ON t.id = tm.team_id
                            WHERE t.org_id = m.org_id AND tm.account_id = m.account_id
                              AND tm.state IN ('ACTIVE', 'SUSPENDED')))
                        """)
                .param("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .query((rs, rowNum) -> UUID.fromString(rs.getString(1)))
                .list();
    }

    int expireOrg(UUID orgId, Instant now) {
        if (organizations.findForUpdate(orgId).isEmpty()) {
            return 0;
        }
        int expired = 0;
        Set<UUID> touched = new LinkedHashSet<>();
        for (TeamMembership membership : teamMemberships.findDueByOrgId(orgId, now)) {
            expireTeam(membership, now);
            touched.add(membership.accountId());
            expired++;
        }
        orgMemberships.findByOrgIdAndStateAndKindOrderByAccountId(orgId, MembershipState.ACTIVE, MembershipKind.GUEST)
                .forEach(membership -> touched.add(membership.accountId()));
        for (UUID accountId : touched) {
            OrgMembership membership = orgMemberships.findByOrgIdAndAccountId(orgId, accountId).orElse(null);
            if (membership == null || membership.state() != MembershipState.ACTIVE
                    || membership.kind() != MembershipKind.GUEST) {
                continue;
            }
            List<TeamMembership> open = teamMemberships.findOpenByOrgIdAndAccountId(orgId, accountId).stream()
                    .filter(TeamMembership::current)
                    .toList();
            if (!open.isEmpty() && (membership.expiresAt() == null || now.isBefore(membership.expiresAt()))) {
                continue;
            }
            for (TeamMembership team : open) {
                expireTeam(team, now);
                expired++;
            }
            membership.expire(now);
            activityLogs.recordOrg(orgId, new ActivityEntry(null, "GUEST_EXPIRED", "ACCOUNT", accountId,
                    MembershipState.ACTIVE.name(), MembershipState.EXPIRED.name()));
            expired++;
        }
        return expired;
    }

    private void expireTeam(TeamMembership membership, Instant now) {
        String before = membership.state().name();
        membership.expire(now);
        activityLogs.recordTeam(membership.teamId(), new ActivityEntry(null, "GUEST_EXPIRED", "ACCOUNT",
                membership.accountId(), before, MembershipState.EXPIRED.name()));
    }

}
