package reberu.core.tenancy.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.audit.ActivityEntry;
import reberu.core.audit.ActivityLogs;
import reberu.core.support.ErrorCode;
import reberu.core.support.FieldReason;
import reberu.core.support.FieldViolation;
import reberu.core.support.ReberuException;
import reberu.core.tenancy.entity.Team;
import reberu.core.tenancy.entity.TenancyCodes.TeamJoinMode;
import reberu.core.tenancy.entity.TenancyCodes.TeamState;
import reberu.core.tenancy.entity.TenancyCodes.Visibility;
import reberu.core.tenancy.repository.TeamRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public class TeamSettingsService {

    public static final Duration GUEST_EXPIRY_SOON = Duration.ofDays(7);
    public static final int RECENT_ACTIVITY_LIMIT = 5;

    private static final String NAME_KEY_CONSTRAINT = "team_org_id_name_key_key";
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final TeamRepository teams;
    private final StateLocks locks;
    private final NameRules nameRules;
    private final ActivityLogs activityLogs;
    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    TeamSettingsService(TeamRepository teams, StateLocks locks, NameRules nameRules, ActivityLogs activityLogs, JdbcClient jdbc,
            JsonMapper jsonMapper, Clock clock) {
        this.teams = teams;
        this.locks = locks;
        this.nameRules = nameRules;
        this.activityLogs = activityLogs;
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TeamOverview overview(UUID teamId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long joinRequests = jdbc.sql("SELECT COUNT(*) FROM team_membership WHERE team_id = :team AND state = 'REQUESTED'")
                .param("team", teamId.toString())
                .query(Long.class)
                .single();
        long expiringGuests = jdbc.sql("""
                        SELECT COUNT(*) FROM team_membership
                        WHERE team_id = :team AND state = 'ACTIVE' AND expires_at IS NOT NULL
                          AND expires_at > :now AND expires_at <= :soon
                        """)
                .param("team", teamId.toString())
                .param("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("soon", LocalDateTime.ofInstant(now.plus(GUEST_EXPIRY_SOON), ZoneOffset.UTC))
                .query(Long.class)
                .single();
        List<RecentActivity> recent = activityLogs.recentTeam(teamId, RECENT_ACTIVITY_LIMIT).stream()
                .map(record -> new RecentActivity(record.occurredAt(), record.actorId(), record.action(),
                        record.targetType(), record.targetId()))
                .toList();
        return new TeamOverview(joinRequests, expiringGuests, recent);
    }

    @Transactional(readOnly = true)
    public TeamSettings settings(UUID orgId, UUID teamId) {
        return view(find(orgId, teamId));
    }

    @Transactional
    public TeamSettings update(UUID orgId, UUID teamId, UUID actorId, SettingsChange change) {
        Team team = locks.activeTeam(orgId, teamId);
        if (change.version() == null || change.version() != team.version()) {
            throw new ReberuException(ErrorCode.VERSION_CONFLICT);
        }
        Values before = Values.of(team, titles(team));
        Values after = merge(orgId, team, before, change);
        if (after.equals(before)) {
            return view(team);
        }
        team.applySettings(after.name(), NameRules.nameKey(after.name()), after.visibility(), after.joinMode(),
                after.reportWeekStart(), jsonMapper.writeValueAsString(after.noticeTitles()), clock.instant());
        try {
            teams.saveAndFlush(team);
        }
        catch (DataIntegrityViolationException violation) {
            if (!UniqueViolations.of(violation, NAME_KEY_CONSTRAINT)) {
                throw violation;
            }
            ReberuException duplicated = new ReberuException(ErrorCode.INVALID_REQUEST,
                    List.of(new FieldViolation("name", FieldReason.DUPLICATE)));
            duplicated.initCause(violation);
            throw duplicated;
        }
        List<Field> changed = Field.ALL.stream()
                .filter(field -> !Objects.equals(field.value().apply(before), field.value().apply(after)))
                .toList();
        activityLogs.recordTeam(teamId, new ActivityEntry(actorId, "TEAM_SETTINGS_CHANGED", "TEAM", teamId,
                describe(before, changed), describe(after, changed)));
        return view(team);
    }

    private Values merge(UUID orgId, Team team, Values current, SettingsChange change) {
        List<FieldViolation> violations = new ArrayList<>();
        String name = current.name();
        if (change.name() != null) {
            FieldViolation invalid = nameRules.checkName("name", change.name(), 1, Team.NAME_MAX);
            if (invalid == null) {
                String nameKey = NameRules.nameKey(change.name());
                if (!nameKey.equals(team.nameKey()) && teams.existsByOrgIdAndNameKey(orgId, nameKey)) {
                    invalid = new FieldViolation("name", FieldReason.DUPLICATE);
                }
            }
            add(violations, invalid);
            name = change.name().strip();
        }
        int weekStart = change.reportWeekStart() == null ? current.reportWeekStart() : change.reportWeekStart();
        if (weekStart < 1 || weekStart > 7) {
            violations.add(new FieldViolation("reportWeekStart", FieldReason.NOT_ALLOWED_VALUE));
        }
        List<String> titles = current.noticeTitles();
        if (change.noticeTitles() != null) {
            int before = violations.size();
            JobTypeService.checkList(violations, "noticeTitles", change.noticeTitles(),
                    JobTypeService.NOTICE_TITLES_MAX, value -> textReason(value, JobTypeService.NOTICE_TITLE_MAX));
            if (violations.size() == before) {
                titles = change.noticeTitles().stream().map(String::strip).toList();
                if (jsonMapper.writeValueAsString(titles).length() > Team.NOTICE_TITLES_MAX) {
                    violations.add(new FieldViolation("noticeTitles", FieldReason.TOO_LONG));
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST, violations);
        }
        return new Values(name, valueOr(change.visibility(), current.visibility()),
                valueOr(change.joinMode(), current.joinMode()), weekStart, titles);
    }

    private FieldReason textReason(String value, int max) {
        FieldViolation violation = nameRules.checkName("item", value, 1, max);
        return violation == null ? null : violation.reason();
    }

    private TeamSettings view(Team team) {
        String jobTypeName = team.jobTypeId() == null ? null
                : jdbc.sql("SELECT name FROM job_type WHERE org_id = :org AND id = :id")
                        .param("org", team.orgId().toString())
                        .param("id", team.jobTypeId().toString())
                        .query(String.class)
                        .optional()
                        .orElse(null);
        return new TeamSettings(team.name(), jobTypeName, team.visibility(), team.joinMode(), team.reportWeekStart(),
                titles(team), team.state(), team.version());
    }

    private List<String> titles(Team team) {
        return jsonMapper.readValue(team.reportNoticeTitles(), STRINGS);
    }

    private Team find(UUID orgId, UUID teamId) {
        return teams.findByOrgIdAndId(orgId, teamId).orElseThrow(TeamSettingsService::notFound);
    }

    private static String describe(Values values, List<Field> changed) {
        List<String> parts = new ArrayList<>();
        for (Field field : changed) {
            parts.add(field.logged() ? field.name() + "=" + field.value().apply(values) : field.name());
        }
        return String.join(";", parts);
    }

    private static <T> T valueOr(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private static void add(List<FieldViolation> violations, FieldViolation violation) {
        if (violation != null) {
            violations.add(violation);
        }
    }

    private static ReberuException notFound() {
        return new ReberuException(ErrorCode.NOT_FOUND);
    }

    private record Values(String name, Visibility visibility, TeamJoinMode joinMode, int reportWeekStart,
            List<String> noticeTitles) {

        static Values of(Team team, List<String> titles) {
            return new Values(team.name(), team.visibility(), team.joinMode(), team.reportWeekStart(), titles);
        }

    }

    private record Field(String name, Function<Values, Object> value, boolean logged) {

        static final List<Field> ALL = List.of(
                new Field("name", Values::name, false),
                new Field("visibility", Values::visibility, true),
                new Field("joinMode", Values::joinMode, true),
                new Field("reportWeekStart", Values::reportWeekStart, true),
                new Field("noticeTitles", Values::noticeTitles, false));

    }

    public record SettingsChange(Integer version, String name, Visibility visibility, TeamJoinMode joinMode,
            Integer reportWeekStart, List<String> noticeTitles) {
    }

    public record TeamSettings(String name, String jobTypeName, Visibility visibility, TeamJoinMode joinMode,
            int reportWeekStart, List<String> noticeTitles, TeamState state, int version) {
    }

    public record RecentActivity(Instant occurredAt, UUID actorId, String action, String targetType, UUID targetId) {
    }

    public record TeamOverview(long pendingJoinRequests, long expiringGuests, List<RecentActivity> recentActivity) {
    }

}
