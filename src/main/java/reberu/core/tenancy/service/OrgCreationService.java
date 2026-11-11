package reberu.core.tenancy.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.account.AccountState;
import reberu.core.account.Accounts;
import reberu.core.audit.ActivityEntry;
import reberu.core.audit.ActivityLogs;
import reberu.core.support.ErrorCode;
import reberu.core.support.FieldReason;
import reberu.core.support.FieldViolation;
import reberu.core.support.ReberuException;
import reberu.core.support.Uuids;
import reberu.core.tenancy.entity.JobType;
import reberu.core.tenancy.entity.OrgMembership;
import reberu.core.tenancy.entity.Organization;
import reberu.core.tenancy.entity.TenancyCodes.JoinedVia;
import reberu.core.tenancy.entity.TenancyCodes.OrgState;
import reberu.core.tenancy.entity.TenancyCodes.TeamRole;
import reberu.core.tenancy.entity.TenancyCodes.Visibility;
import reberu.core.tenancy.entity.Team;
import reberu.core.tenancy.entity.TeamMembership;
import reberu.core.tenancy.repository.OrgMembershipRepository;
import reberu.core.tenancy.repository.OrganizationRepository;
import reberu.core.tenancy.repository.TeamMembershipRepository;
import reberu.core.tenancy.repository.TeamRepository;

public class OrgCreationService {

    public static final int ORG_LIMIT = 5;

    private final Accounts accounts;
    private final OrganizationRepository organizations;
    private final OrgMembershipRepository orgMemberships;
    private final TeamRepository teams;
    private final TeamMembershipRepository teamMemberships;
    private final DefaultJobTypes defaultJobTypes;
    private final TeamFieldService teamFields;
    private final NameRules nameRules;
    private final ActivityLogs activityLogs;
    private final Clock clock;

    OrgCreationService(Accounts accounts, OrganizationRepository organizations,
            OrgMembershipRepository orgMemberships, TeamRepository teams, TeamMembershipRepository teamMemberships,
            DefaultJobTypes defaultJobTypes, TeamFieldService teamFields, NameRules nameRules,
            ActivityLogs activityLogs, Clock clock) {
        this.accounts = accounts;
        this.organizations = organizations;
        this.orgMemberships = orgMemberships;
        this.teams = teams;
        this.teamMemberships = teamMemberships;
        this.defaultJobTypes = defaultJobTypes;
        this.teamFields = teamFields;
        this.nameRules = nameRules;
        this.activityLogs = activityLogs;
        this.clock = clock;
    }

    @Transactional
    public CreatedOrg create(UUID accountId, NewOrg input) {
        Objects.requireNonNull(accountId, "계정 ID 없음");
        Objects.requireNonNull(input, "조직 입력 없음");
        if (accounts.lockState(accountId).filter(AccountState.ACTIVE::equals).isEmpty()) {
            throw new ReberuException(ErrorCode.UNAUTHENTICATED);
        }
        if (organizations.countByCreatedByAndStateNot(accountId, OrgState.DELETED) >= ORG_LIMIT) {
            throw new ReberuException(ErrorCode.LIMIT_EXCEEDED);
        }
        validate(input);

        Instant now = clock.instant();
        Organization organization = organizations.save(Organization.create(Uuids.v7(), input.name().strip(),
                blankToNull(input.tagline()), input.visibility(), input.timeZone(), accountId, now));
        Map<String, JobType> jobTypes = defaultJobTypes.copyTo(organization.id(), now);
        orgMemberships.save(OrgMembership.activeAdmin(Uuids.v7(), organization.id(), accountId, JoinedVia.CREATOR, now));

        DefaultJobTypes.DefaultJobType selected = DefaultJobTypes.find(input.teamJobTypeKey()).orElseThrow();
        String teamName = input.teamName().strip();
        Team team = teams.save(Team.create(Uuids.v7(), organization.id(), teamName, NameRules.nameKey(teamName),
                jobTypes.get(selected.key()).id(), selected.defaults().reportWeekStart(),
                defaultJobTypes.noticeTitles(selected), now));
        teamFields.copyLabels(team.id(), selected.defaults().taskLabels(), now);
        teamMemberships.save(TeamMembership.active(Uuids.v7(), team.id(), accountId, TeamRole.TEAM_ADMIN,
                JoinedVia.CREATOR, now));

        activityLogs.recordOrg(organization.id(),
                new ActivityEntry(accountId, "ORG_CREATED", "ORG", organization.id(), null, null));
        activityLogs.recordOrg(organization.id(),
                new ActivityEntry(accountId, "ORG_MEMBER_JOINED", "ACCOUNT", accountId, null, "ORG_ADMIN"));
        activityLogs.recordOrg(organization.id(),
                new ActivityEntry(accountId, "TEAM_CREATED", "TEAM", team.id(), null, null));
        activityLogs.recordTeam(team.id(),
                new ActivityEntry(accountId, "TEAM_MEMBER_JOINED", "ACCOUNT", accountId, null, "TEAM_ADMIN"));
        return new CreatedOrg(organization.id(), team.id());
    }

    private void validate(NewOrg input) {
        List<FieldViolation> violations = new ArrayList<>();
        add(violations, nameRules.checkName("name", input.name(), Organization.NAME_MIN, Organization.NAME_MAX));
        add(violations, nameRules.checkOptionalText("tagline", input.tagline(), Organization.TAGLINE_MAX));
        if (input.visibility() == null) {
            violations.add(new FieldViolation("visibility", FieldReason.REQUIRED));
        }
        if (input.timeZone() == null || input.timeZone().isBlank()) {
            violations.add(new FieldViolation("timeZone", FieldReason.REQUIRED));
        } else if (!TenancyProperties.validTimeZone(input.timeZone())) {
            violations.add(new FieldViolation("timeZone", FieldReason.NOT_ALLOWED_VALUE));
        }
        add(violations, nameRules.checkName("firstTeam.name", input.teamName(), 1, Team.NAME_MAX));
        if (input.teamJobTypeKey() == null || input.teamJobTypeKey().isBlank()) {
            violations.add(new FieldViolation("firstTeam.jobTypeKey", FieldReason.REQUIRED));
        } else if (DefaultJobTypes.find(input.teamJobTypeKey()).isEmpty()) {
            violations.add(new FieldViolation("firstTeam.jobTypeKey", FieldReason.NOT_ALLOWED_VALUE));
        }
        if (!violations.isEmpty()) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST, violations);
        }
    }

    private static void add(List<FieldViolation> violations, FieldViolation violation) {
        if (violation != null) {
            violations.add(violation);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record NewOrg(String name, String tagline, Visibility visibility, String timeZone, String teamName,
            String teamJobTypeKey) {
    }

    public record CreatedOrg(UUID orgId, UUID teamId) {
    }

}
