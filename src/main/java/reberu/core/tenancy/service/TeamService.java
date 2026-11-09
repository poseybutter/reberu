package reberu.core.tenancy.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.audit.ActivityEntry;
import reberu.core.audit.ActivityLogs;
import reberu.core.support.CursorCodec;
import reberu.core.support.CursorPage;
import reberu.core.support.ErrorCode;
import reberu.core.support.FieldReason;
import reberu.core.support.FieldViolation;
import reberu.core.support.ReberuException;
import reberu.core.support.Uuids;
import reberu.core.tenancy.entity.JobType;
import reberu.core.tenancy.entity.OrgMembership;
import reberu.core.tenancy.entity.TenancyCodes.JoinedVia;
import reberu.core.tenancy.entity.TenancyCodes.MembershipKind;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;
import reberu.core.tenancy.entity.TenancyCodes.TeamRole;
import reberu.core.tenancy.entity.TenancyCodes.TeamState;
import reberu.core.tenancy.entity.TenancyCodes.Visibility;
import reberu.core.tenancy.entity.Team;
import reberu.core.tenancy.entity.TeamMembership;
import reberu.core.tenancy.repository.JobTypeRepository;
import reberu.core.tenancy.repository.OrgMembershipRepository;
import reberu.core.tenancy.repository.TeamMembershipRepository;
import reberu.core.tenancy.repository.TeamRepository;
import tools.jackson.databind.json.JsonMapper;

public class TeamService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private final OrgAccess access;
    private final TeamRepository teams;
    private final TeamMembershipRepository teamMemberships;
    private final OrgMembershipRepository orgMemberships;
    private final JobTypeRepository jobTypes;
    private final NameRules nameRules;
    private final ActivityLogs activityLogs;
    private final CursorCodec cursors;
    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    TeamService(OrgAccess access, TeamRepository teams, TeamMembershipRepository teamMemberships,
            OrgMembershipRepository orgMemberships, JobTypeRepository jobTypes, NameRules nameRules,
            ActivityLogs activityLogs, CursorCodec cursors, JdbcClient jdbc, JsonMapper jsonMapper, Clock clock) {
        this.access = access;
        this.teams = teams;
        this.teamMemberships = teamMemberships;
        this.orgMemberships = orgMemberships;
        this.jobTypes = jobTypes;
        this.nameRules = nameRules;
        this.activityLogs = activityLogs;
        this.cursors = cursors;
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CursorPage<TeamSummary> list(UUID orgId, UUID accountId, String cursor, int limit) {
        OrgAccess.Member member = access.requireMember(orgId, accountId);
        String scope = "org-teams:" + orgId;
        Position after = cursor == null ? null : position(cursors.decode(scope, cursor));
        String visibleFilter = member.membership().orgAdmin() ? "" : """
                 AND (t.visibility = 'PUBLIC' OR EXISTS (SELECT 1 FROM team_membership v
                      WHERE v.team_id = t.id AND v.account_id = :account AND v.state = 'ACTIVE'))
                """;
        String afterFilter = after == null ? ""
                : " AND (t.created_at > :afterAt OR (t.created_at = :afterAt AND t.id > :afterId))";
        JdbcClient.StatementSpec query = jdbc.sql("""
                        SELECT t.id, t.name, t.visibility, t.state, t.created_at, j.name AS job_type_name,
                               (SELECT COUNT(*) FROM team_membership c
                                WHERE c.team_id = t.id AND c.state = 'ACTIVE') AS member_count,
                               (SELECT r.role FROM team_membership r
                                WHERE r.team_id = t.id AND r.account_id = :account AND r.state = 'ACTIVE') AS my_role
                        FROM team t
                        LEFT JOIN job_type j ON j.id = t.job_type_id AND j.org_id = t.org_id
                        WHERE t.org_id = :org
                        """ + visibleFilter + afterFilter + " ORDER BY t.created_at, t.id LIMIT :limit")
                .param("org", orgId.toString())
                .param("account", accountId.toString())
                .param("limit", limit + 1);
        if (after != null) {
            query = query.param("afterAt", after.createdAt()).param("afterId", after.id());
        }
        List<TeamSummary> rows = query.query(TeamService::summary).list();
        if (rows.size() <= limit) {
            return new CursorPage<>(rows, null);
        }
        List<TeamSummary> page = rows.subList(0, limit);
        TeamSummary last = page.get(limit - 1);
        return new CursorPage<>(List.copyOf(page), cursors.encode(scope,
                List.of(LocalDateTime.ofInstant(last.createdAt(), ZoneOffset.UTC).toString(), last.id().toString())));
    }

    @Transactional
    public UUID create(UUID orgId, UUID accountId, NewTeam input) {
        OrgAccess.Member admin = access.requireAdmin(orgId, accountId);
        OrgAccess.requireWritable(admin.organization());
        List<FieldViolation> violations = new ArrayList<>();
        FieldViolation name = nameRules.checkName("name", input.name(), 1, Team.NAME_MAX);
        String nameKey = name == null ? NameRules.nameKey(input.name()) : null;
        if (name == null && teams.existsByOrgIdAndNameKey(orgId, nameKey)) {
            name = new FieldViolation("name", FieldReason.DUPLICATE);
        }
        if (name != null) {
            violations.add(name);
        }
        if (input.visibility() == null) {
            violations.add(new FieldViolation("visibility", FieldReason.REQUIRED));
        }
        Optional<JobType> jobType = Optional.ofNullable(input.jobTypeId())
                .flatMap(id -> jobTypes.findByOrgIdAndId(orgId, id))
                .filter(found -> !found.hidden());
        if (jobType.isEmpty()) {
            violations.add(new FieldViolation("jobTypeId",
                    input.jobTypeId() == null ? FieldReason.REQUIRED : FieldReason.NOT_ALLOWED_VALUE));
        }
        if (input.firstAdminAccountId() == null) {
            violations.add(new FieldViolation("firstAdminAccountId", FieldReason.REQUIRED));
        } else if (!canBeTeamAdmin(orgId, input.firstAdminAccountId())) {
            violations.add(new FieldViolation("firstAdminAccountId", FieldReason.NOT_ALLOWED_VALUE));
        }
        if (!violations.isEmpty()) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST, violations);
        }

        Instant now = clock.instant();
        DefaultJobTypes.JobTypeDefaults defaults =
                jsonMapper.readValue(jobType.get().defaults(), DefaultJobTypes.JobTypeDefaults.class);
        String teamName = input.name().strip();
        Team team = Team.create(Uuids.v7(), orgId, teamName, nameKey, jobType.get().id(),
                defaults.reportWeekStart(), jsonMapper.writeValueAsString(defaults.noticeTitles()), now);
        if (input.visibility() == Visibility.PUBLIC) {
            team.makePublic(now);
        }
        try {
            teams.saveAndFlush(team);
        }
        catch (DataIntegrityViolationException duplicated) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST,
                    List.of(new FieldViolation("name", FieldReason.DUPLICATE)));
        }
        teamMemberships.save(TeamMembership.active(Uuids.v7(), team.id(), input.firstAdminAccountId(),
                TeamRole.TEAM_ADMIN, JoinedVia.DIRECT, now));
        activityLogs.recordOrg(orgId, new ActivityEntry(accountId, "TEAM_CREATED", "TEAM", team.id(), null, null));
        activityLogs.recordTeam(team.id(), new ActivityEntry(accountId, "TEAM_MEMBER_JOINED", "ACCOUNT",
                input.firstAdminAccountId(), null, "TEAM_ADMIN"));
        return team.id();
    }

    @Transactional
    public void archive(UUID orgId, UUID teamId, UUID accountId) {
        OrgAccess.Member admin = access.requireAdmin(orgId, accountId);
        OrgAccess.requireWritable(admin.organization());
        Team team = teams.findForUpdate(orgId, teamId).orElseThrow(OrgAccess::notFound);
        if (team.state() != TeamState.ACTIVE) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        team.archive(clock.instant());
        activityLogs.recordOrg(orgId, new ActivityEntry(accountId, "TEAM_ARCHIVED", "TEAM", teamId,
                "ACTIVE", "ARCHIVED"));
    }

    @Transactional
    public void restore(UUID orgId, UUID teamId, UUID accountId, UUID newAdminAccountId) {
        OrgAccess.Member admin = access.requireAdmin(orgId, accountId);
        OrgAccess.requireWritable(admin.organization());
        Team team = teams.findForUpdate(orgId, teamId).orElseThrow(OrgAccess::notFound);
        if (team.state() != TeamState.ARCHIVED) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        long admins = teamMemberships.countByTeamIdAndRoleAndState(teamId, TeamRole.TEAM_ADMIN,
                MembershipState.ACTIVE);
        Instant now = clock.instant();
        if (admins > 0 && newAdminAccountId != null) {
            throw invalid("newAdminAccountId", FieldReason.NOT_ALLOWED_VALUE);
        }
        if (admins == 0) {
            if (newAdminAccountId == null) {
                throw invalid("newAdminAccountId", FieldReason.REQUIRED);
            }
            assignAdmin(orgId, team, accountId, newAdminAccountId, now);
        }
        team.restore(now);
        activityLogs.recordOrg(orgId, new ActivityEntry(accountId, "TEAM_RESTORED", "TEAM", teamId,
                "ARCHIVED", "ACTIVE"));
    }

    private void assignAdmin(UUID orgId, Team team, UUID actorId, UUID newAdminAccountId, Instant now) {
        if (!canBeTeamAdmin(orgId, newAdminAccountId)) {
            throw invalid("newAdminAccountId", FieldReason.NOT_ALLOWED_VALUE);
        }
        Optional<TeamMembership> existing = teamMemberships.findByTeamIdAndAccountId(team.id(), newAdminAccountId);
        if (existing.isPresent()) {
            if (existing.get().state() != MembershipState.ACTIVE) {
                throw invalid("newAdminAccountId", FieldReason.NOT_ALLOWED_VALUE);
            }
            String before = existing.get().role().name();
            existing.get().promoteToAdmin(now);
            activityLogs.recordTeam(team.id(), new ActivityEntry(actorId, "TEAM_ROLE_CHANGED", "ACCOUNT",
                    newAdminAccountId, before, "TEAM_ADMIN"));
            return;
        }
        teamMemberships.save(TeamMembership.active(Uuids.v7(), team.id(), newAdminAccountId, TeamRole.TEAM_ADMIN,
                JoinedVia.DIRECT, now));
        activityLogs.recordTeam(team.id(), new ActivityEntry(actorId, "TEAM_MEMBER_JOINED", "ACCOUNT",
                newAdminAccountId, null, "TEAM_ADMIN"));
    }

    private static Position position(List<String> values) {
        try {
            if (values.size() == 2) {
                LocalDateTime createdAt = LocalDateTime.parse(values.get(0));
                String id = values.get(1);
                if (UUID.fromString(id).toString().equals(id)) {
                    return new Position(createdAt, id);
                }
            }
        }
        catch (IllegalArgumentException | DateTimeParseException malformed) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST);
        }
        throw new ReberuException(ErrorCode.INVALID_REQUEST);
    }

    private boolean canBeTeamAdmin(UUID orgId, UUID accountId) {
        return orgMemberships.findByOrgIdAndAccountId(orgId, accountId)
                .filter(membership -> membership.state() == MembershipState.ACTIVE)
                .map(OrgMembership::kind)
                .filter(MembershipKind.MEMBER::equals)
                .isPresent();
    }

    private static ReberuException invalid(String field, FieldReason reason) {
        return new ReberuException(ErrorCode.INVALID_REQUEST, List.of(new FieldViolation(field, reason)));
    }

    private static TeamSummary summary(ResultSet rs, int rowNum) throws SQLException {
        LocalDateTime createdAt = rs.getObject("created_at", LocalDateTime.class);
        String role = rs.getString("my_role");
        return new TeamSummary(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                Visibility.valueOf(rs.getString("visibility")),
                TeamState.valueOf(rs.getString("state")),
                rs.getString("job_type_name"),
                rs.getLong("member_count"),
                role == null ? null : TeamRole.valueOf(role),
                createdAt.toInstant(ZoneOffset.UTC));
    }

    private record Position(LocalDateTime createdAt, String id) {
    }

    public record NewTeam(String name, Visibility visibility, UUID jobTypeId, UUID firstAdminAccountId) {
    }

    public record TeamSummary(UUID id, String name, Visibility visibility, TeamState state, String jobTypeName,
            long memberCount, TeamRole myRole, Instant createdAt) {
    }

}
