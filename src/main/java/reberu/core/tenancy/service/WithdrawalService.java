package reberu.core.tenancy.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.account.AccountProfile;
import reberu.core.account.AccountState;
import reberu.core.account.Accounts;
import reberu.core.audit.ActivityEntry;
import reberu.core.audit.ActivityLogs;
import reberu.core.support.ErrorCode;
import reberu.core.support.FieldReason;
import reberu.core.support.FieldViolation;
import reberu.core.support.ReberuException;
import reberu.core.tenancy.entity.OrgMembership;
import reberu.core.tenancy.entity.Organization;
import reberu.core.tenancy.entity.TeamMembership;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;
import reberu.core.tenancy.entity.TenancyCodes.OrgState;
import reberu.core.tenancy.repository.OrgMembershipRepository;
import reberu.core.tenancy.repository.OrganizationRepository;
import reberu.core.tenancy.repository.TeamMembershipRepository;
import reberu.core.tenancy.repository.TeamRepository;

public class WithdrawalService {

    public static final String CONFIRM_TEXT = "탈퇴합니다";

    private static final String CONFIRMATIONS = "orgNameConfirmations";

    private final Accounts accounts;
    private final OrganizationRepository organizations;
    private final OrgMembershipRepository orgMemberships;
    private final TeamRepository teams;
    private final TeamMembershipRepository teamMemberships;
    private final OrgSettingsService orgSettings;
    private final TeamService teamService;
    private final InvitationService invitations;
    private final ActivityLogs activityLogs;
    private final TenancyProperties properties;
    private final JdbcClient jdbc;
    private final Clock clock;

    WithdrawalService(Accounts accounts, OrganizationRepository organizations, OrgMembershipRepository orgMemberships,
            TeamRepository teams, TeamMembershipRepository teamMemberships, OrgSettingsService orgSettings,
            TeamService teamService, InvitationService invitations, ActivityLogs activityLogs,
            TenancyProperties properties, JdbcClient jdbc, Clock clock) {
        this.accounts = accounts;
        this.organizations = organizations;
        this.orgMemberships = orgMemberships;
        this.teams = teams;
        this.teamMemberships = teamMemberships;
        this.orgSettings = orgSettings;
        this.teamService = teamService;
        this.invitations = invitations;
        this.activityLogs = activityLogs;
        this.properties = properties;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public WithdrawalCheck check(UUID accountId) {
        return judge(accountId);
    }

    @Transactional
    public void withdraw(UUID accountId, String confirmText, List<OrgName> orgNames) {
        if (!CONFIRM_TEXT.equals(confirmText)) {
            throw invalid("confirmText", FieldReason.NOT_ALLOWED_VALUE);
        }
        Map<UUID, String> confirmed = confirmations(orgNames);
        if (accounts.lockState(accountId).filter(AccountState.ACTIVE::equals).isEmpty()) {
            throw new ReberuException(ErrorCode.UNAUTHENTICATED);
        }
        Set<UUID> orgIds = relatedOrgIds(accountId);
        orgIds.forEach(organizations::findForUpdate);
        if (!orgIds.equals(relatedOrgIds(accountId))) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        adminTeams(accountId).forEach(team -> teams.findForUpdate(team.orgId(), team.teamId()));

        WithdrawalCheck judgment = judge(accountId);
        Map<UUID, String> expected = judgment.archiveOrgs().stream()
                .collect(Collectors.toMap(OrgItem::orgId, OrgItem::name));
        if (!expected.keySet().equals(confirmed.keySet())) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        if (!judgment.withdrawable()) {
            throw new ReberuException(ErrorCode.LAST_ADMIN_REQUIRED);
        }
        if (expected.entrySet().stream().anyMatch(org -> !org.getValue().equals(confirmed.get(org.getKey())))) {
            throw invalid(CONFIRMATIONS, FieldReason.NOT_ALLOWED_VALUE);
        }

        Instant now = clock.instant();
        AccountProfile profile = accounts.profiles(Set.of(accountId)).get(accountId);
        endMemberships(accountId, orgIds, now);
        for (OrgItem org : judgment.archiveOrgs()) {
            abandon(organizations.findForUpdate(org.orgId()).orElseThrow(), accountId, now);
        }
        for (TeamItem team : judgment.archiveTeams()) {
            teamService.archiveLocked(team.orgId(), teams.findForUpdate(team.orgId(), team.teamId()).orElseThrow(),
                    accountId, now);
        }
        invitations.forget(accountId, profile == null ? null : profile.email(), now);
        accounts.withdraw(accountId, now);
    }

    private void endMemberships(UUID accountId, Set<UUID> orgIds, Instant now) {
        for (UUID orgId : orgIds) {
            for (TeamMembership membership : teamMemberships.findOpenByOrgIdAndAccountId(orgId, accountId)) {
                String before = membership.state().name();
                membership.withdraw(now);
                activityLogs.recordTeam(membership.teamId(), new ActivityEntry(accountId, "TEAM_MEMBER_WITHDRAWN",
                        "ACCOUNT", accountId, before, MembershipState.LEFT.name()));
            }
            OrgMembership membership = orgMemberships.findByOrgIdAndAccountId(orgId, accountId).orElse(null);
            if (membership != null && (membership.current() || membership.state() == MembershipState.REQUESTED)) {
                String before = membership.state().name();
                membership.withdraw(now);
                activityLogs.recordOrg(orgId, new ActivityEntry(accountId, "ORG_MEMBER_WITHDRAWN", "ACCOUNT",
                        accountId, before, MembershipState.LEFT.name()));
            }
        }
    }

    private void abandon(Organization organization, UUID accountId, Instant now) {
        String before = organization.state().name();
        if (organization.state() == OrgState.ACTIVE) {
            orgSettings.archiveLocked(organization, accountId, now);
        }
        organization.abandon(now);
        invitations.revokeOpenForOrg(organization.id(), accountId, now);
        activityLogs.recordOrg(organization.id(), new ActivityEntry(accountId, "ORG_ABANDONED", "ORG",
                organization.id(), before, "OWNERLESS"));
    }

    private WithdrawalCheck judge(UUID accountId) {
        List<OrgItem> transferOrgs = new ArrayList<>();
        List<OrgItem> archiveOrgs = new ArrayList<>();
        for (SoleAdmin org : soleAdminOrgs(accountId)) {
            if (org.others() > 0) {
                transferOrgs.add(org.item());
            }
            else if (!properties.singleOrgMode()) {
                archiveOrgs.add(org.item());
            }
        }
        Set<UUID> closing = archiveOrgs.stream().map(OrgItem::orgId).collect(Collectors.toSet());
        List<TeamItem> transferTeams = new ArrayList<>();
        List<TeamItem> archiveTeams = new ArrayList<>();
        for (SoleTeamAdmin team : soleAdminTeams(accountId)) {
            if (closing.contains(team.item().orgId())) {
                continue;
            }
            (team.others() > 0 ? transferTeams : archiveTeams).add(team.item());
        }
        return new WithdrawalCheck(transferOrgs.isEmpty() && transferTeams.isEmpty(), CONFIRM_TEXT, transferOrgs,
                transferTeams, archiveOrgs, archiveTeams);
    }

    private Set<UUID> relatedOrgIds(UUID accountId) {
        return new TreeSet<>(jdbc.sql("""
                        SELECT org_id FROM org_membership
                        WHERE account_id = :account AND state IN ('ACTIVE', 'SUSPENDED', 'REQUESTED')
                        UNION
                        SELECT t.org_id FROM team_membership m JOIN team t ON t.id = m.team_id
                        WHERE m.account_id = :account AND m.state IN ('ACTIVE', 'SUSPENDED', 'REQUESTED')
                        """)
                .param("account", accountId.toString())
                .query((rs, rowNum) -> UUID.fromString(rs.getString("org_id")))
                .list());
    }

    private List<TeamItem> adminTeams(UUID accountId) {
        return jdbc.sql("""
                        SELECT t.org_id, t.id, t.name FROM team_membership m JOIN team t ON t.id = m.team_id
                        WHERE m.account_id = :account AND m.state = 'ACTIVE' AND m.role = 'TEAM_ADMIN'
                        """)
                .param("account", accountId.toString())
                .query((rs, rowNum) -> new TeamItem(UUID.fromString(rs.getString("org_id")), null,
                        UUID.fromString(rs.getString("id")), rs.getString("name")))
                .list()
                .stream()
                .sorted((left, right) -> left.teamId().compareTo(right.teamId()))
                .toList();
    }

    private List<SoleAdmin> soleAdminOrgs(UUID accountId) {
        return jdbc.sql("""
                        SELECT o.id, o.name, o.state,
                            (SELECT COUNT(*) FROM org_membership x
                             WHERE x.org_id = o.id AND x.account_id <> :account AND x.state = 'ACTIVE') AS others
                        FROM org_membership m JOIN organization o ON o.id = m.org_id
                        WHERE m.account_id = :account AND m.state = 'ACTIVE' AND m.org_admin = :admin
                          AND o.state IN ('ACTIVE', 'ARCHIVED')
                          AND NOT EXISTS (
                              SELECT 1 FROM org_membership a
                              WHERE a.org_id = o.id AND a.account_id <> :account AND a.state = 'ACTIVE'
                                AND a.org_admin = :admin)
                        ORDER BY o.id
                        """)
                .param("account", accountId.toString())
                .param("admin", true)
                .query((rs, rowNum) -> new SoleAdmin(new OrgItem(UUID.fromString(rs.getString("id")),
                        rs.getString("name"), OrgState.valueOf(rs.getString("state"))), rs.getLong("others")))
                .list();
    }

    private List<SoleTeamAdmin> soleAdminTeams(UUID accountId) {
        return jdbc.sql("""
                        SELECT t.org_id, o.name AS org_name, t.id, t.name,
                            (SELECT COUNT(*) FROM team_membership x
                             JOIN org_membership xo ON xo.org_id = t.org_id AND xo.account_id = x.account_id
                             WHERE x.team_id = t.id AND x.account_id <> :account AND x.state = 'ACTIVE'
                               AND xo.state = 'ACTIVE') AS others
                        FROM team_membership m
                        JOIN team t ON t.id = m.team_id
                        JOIN organization o ON o.id = t.org_id
                        JOIN org_membership om ON om.org_id = t.org_id AND om.account_id = m.account_id
                        WHERE m.account_id = :account AND m.state = 'ACTIVE' AND m.role = 'TEAM_ADMIN'
                          AND om.state = 'ACTIVE' AND t.state = 'ACTIVE' AND o.state IN ('ACTIVE', 'ARCHIVED')
                          AND NOT EXISTS (
                              SELECT 1 FROM team_membership a
                              JOIN org_membership ao ON ao.org_id = t.org_id AND ao.account_id = a.account_id
                              WHERE a.team_id = t.id AND a.account_id <> :account AND a.state = 'ACTIVE'
                                AND a.role = 'TEAM_ADMIN' AND ao.state = 'ACTIVE')
                        ORDER BY t.id
                        """)
                .param("account", accountId.toString())
                .query((rs, rowNum) -> new SoleTeamAdmin(new TeamItem(UUID.fromString(rs.getString("org_id")),
                        rs.getString("org_name"), UUID.fromString(rs.getString("id")), rs.getString("name")),
                        rs.getLong("others")))
                .list();
    }

    private static Map<UUID, String> confirmations(List<OrgName> orgNames) {
        Map<UUID, String> confirmed = new HashMap<>();
        for (OrgName org : orgNames == null ? List.<OrgName>of() : orgNames) {
            if (org == null || org.orgId() == null || org.name() == null) {
                throw invalid(CONFIRMATIONS, FieldReason.REQUIRED);
            }
            if (confirmed.put(org.orgId(), org.name().strip()) != null) {
                throw invalid(CONFIRMATIONS, FieldReason.DUPLICATE);
            }
        }
        return confirmed;
    }

    private static ReberuException invalid(String field, FieldReason reason) {
        return new ReberuException(ErrorCode.INVALID_REQUEST, List.of(new FieldViolation(field, reason)));
    }

    public record OrgName(UUID orgId, String name) {
    }

    public record OrgItem(UUID orgId, String name, OrgState state) {
    }

    public record TeamItem(UUID orgId, String orgName, UUID teamId, String name) {
    }

    public record WithdrawalCheck(boolean withdrawable, String confirmText, List<OrgItem> transferOrgs,
            List<TeamItem> transferTeams, List<OrgItem> archiveOrgs, List<TeamItem> archiveTeams) {
    }

    private record SoleAdmin(OrgItem item, long others) {
    }

    private record SoleTeamAdmin(TeamItem item, long others) {
    }

}
