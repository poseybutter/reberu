package reberu.core.tenancy.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.audit.ActivityEntry;
import reberu.core.audit.ActivityLogs;
import reberu.core.support.ErrorCode;
import reberu.core.support.FieldReason;
import reberu.core.support.FieldViolation;
import reberu.core.support.ReberuException;
import reberu.core.support.Uuids;
import reberu.core.tenancy.entity.JobType;
import reberu.core.tenancy.entity.Team;
import reberu.core.tenancy.repository.JobTypeRepository;
import reberu.core.tenancy.service.DefaultJobTypes.JobTypeDefaults;
import tools.jackson.databind.json.JsonMapper;

public class JobTypeService {

    public static final int NAME_MAX = 40;
    public static final int DESCRIPTION_MAX = 200;
    public static final int PER_ORG_MAX = 50;
    public static final int MODULES_MAX = 20;
    public static final int LABELS_MAX = 20;
    public static final int LABEL_MAX = 30;
    public static final int NOTICE_TITLES_MAX = 5;
    public static final int NOTICE_TITLE_MAX = 40;

    private static final Pattern MODULE_KEY = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    private static final String NAME_KEY_CONSTRAINT = "job_type_org_id_name_key_key";

    private final JobTypeRepository jobTypes;
    private final StateLocks locks;
    private final NameRules nameRules;
    private final ActivityLogs activityLogs;
    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    JobTypeService(JobTypeRepository jobTypes, StateLocks locks, NameRules nameRules,
            ActivityLogs activityLogs, JdbcClient jdbc, JsonMapper jsonMapper, Clock clock) {
        this.jobTypes = jobTypes;
        this.locks = locks;
        this.nameRules = nameRules;
        this.activityLogs = activityLogs;
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<JobTypeView> list(UUID orgId, boolean includeHidden) {
        return jdbc.sql("""
                        SELECT j.id, j.name, j.description, j.hidden, j.defaults, j.version,
                               (SELECT COUNT(*) FROM team t WHERE t.org_id = j.org_id AND t.job_type_id = j.id)
                                   AS team_count
                        FROM job_type j
                        WHERE j.org_id = :org
                        """ + (includeHidden ? "" : " AND j.hidden = :hidden") + " ORDER BY j.created_at, j.id")
                .param("org", orgId.toString())
                .param("hidden", false)
                .query((rs, rowNum) -> new JobTypeView(UUID.fromString(rs.getString("id")), rs.getString("name"),
                        rs.getString("description"), rs.getBoolean("hidden"),
                        jsonMapper.readValue(rs.getString("defaults"), JobTypeDefaults.class),
                        rs.getLong("team_count"), rs.getInt("version")))
                .list();
    }

    @Transactional
    public UUID create(UUID orgId, UUID actorId, NewJobType input) {
        locks.activeOrg(orgId);
        List<FieldViolation> violations = new ArrayList<>();
        FieldViolation name = nameRules.checkName("name", input.name(), 1, NAME_MAX);
        String nameKey = name == null ? NameRules.nameKey(input.name()) : null;
        if (name == null && jobTypes.existsByOrgIdAndNameKey(orgId, nameKey)) {
            name = new FieldViolation("name", FieldReason.DUPLICATE);
        }
        add(violations, name);
        add(violations, nameRules.checkOptionalText("description", input.description(), DESCRIPTION_MAX));
        if (input.defaults() == null) {
            violations.add(new FieldViolation("defaults", FieldReason.REQUIRED));
        } else {
            violations.addAll(checkDefaults(input.defaults()));
        }
        if (!violations.isEmpty()) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST, violations);
        }
        if (jobTypes.countByOrgId(orgId) >= PER_ORG_MAX) {
            throw new ReberuException(ErrorCode.LIMIT_EXCEEDED);
        }
        JobType jobType = JobType.create(Uuids.v7(), orgId, input.name().strip(), nameKey,
                blankToNull(input.description()), jsonMapper.writeValueAsString(normalize(input.defaults())),
                clock.instant());
        save(jobType);
        activityLogs.recordOrg(orgId, new ActivityEntry(actorId, "JOB_TYPE_CREATED", "JOB_TYPE", jobType.id(),
                null, null));
        return jobType.id();
    }

    @Transactional
    public JobTypeView update(UUID orgId, UUID jobTypeId, UUID actorId, JobTypeChange change) {
        locks.activeOrg(orgId);
        JobType jobType = jobTypes.findForUpdate(orgId, jobTypeId).orElseThrow(JobTypeService::notFound);
        if (change.version() == null || change.version() != jobType.version()) {
            throw new ReberuException(ErrorCode.VERSION_CONFLICT);
        }
        List<FieldViolation> violations = new ArrayList<>();
        String name = jobType.name();
        String nameKey = jobType.nameKey();
        if (change.name() != null) {
            FieldViolation invalid = nameRules.checkName("name", change.name(), 1, NAME_MAX);
            if (invalid == null) {
                nameKey = NameRules.nameKey(change.name());
                if (!nameKey.equals(jobType.nameKey()) && jobTypes.existsByOrgIdAndNameKey(orgId, nameKey)) {
                    invalid = new FieldViolation("name", FieldReason.DUPLICATE);
                }
            }
            add(violations, invalid);
            name = change.name().strip();
        }
        String description = jobType.description();
        if (change.description() != null) {
            add(violations, nameRules.checkOptionalText("description", change.description(), DESCRIPTION_MAX));
            description = blankToNull(change.description());
        }
        String defaults = jobType.defaults();
        if (change.defaults() != null) {
            violations.addAll(checkDefaults(change.defaults()));
            defaults = jsonMapper.writeValueAsString(normalize(change.defaults()));
        }
        if (!violations.isEmpty()) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST, violations);
        }
        List<String> changed = new ArrayList<>();
        if (!name.equals(jobType.name())) {
            changed.add("name");
        }
        if (!Objects.equals(description, jobType.description())) {
            changed.add("description");
        }
        if (!defaults.equals(jobType.defaults())) {
            changed.add("defaults");
        }
        if (!changed.isEmpty()) {
            jobType.change(name, nameKey, description, defaults, clock.instant());
            save(jobType);
            activityLogs.recordOrg(orgId, new ActivityEntry(actorId, "JOB_TYPE_CHANGED", "JOB_TYPE", jobTypeId,
                    null, String.join(";", changed)));
        }
        return view(jobType);
    }

    @Transactional
    public void hide(UUID orgId, UUID jobTypeId, UUID actorId) {
        locks.activeOrg(orgId);
        JobType jobType = jobTypes.findForUpdate(orgId, jobTypeId).orElseThrow(JobTypeService::notFound);
        if (jobType.hidden()) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        jobType.hide(clock.instant());
        activityLogs.recordOrg(orgId, new ActivityEntry(actorId, "JOB_TYPE_HIDDEN", "JOB_TYPE", jobTypeId,
                "VISIBLE", "HIDDEN"));
    }

    @Transactional
    public void unhide(UUID orgId, UUID jobTypeId, UUID actorId) {
        locks.activeOrg(orgId);
        JobType jobType = jobTypes.findForUpdate(orgId, jobTypeId).orElseThrow(JobTypeService::notFound);
        if (!jobType.hidden()) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        jobType.unhide(clock.instant());
        activityLogs.recordOrg(orgId, new ActivityEntry(actorId, "JOB_TYPE_UNHIDDEN", "JOB_TYPE", jobTypeId,
                "HIDDEN", "VISIBLE"));
    }

    private void save(JobType jobType) {
        try {
            jobTypes.saveAndFlush(jobType);
        }
        catch (DataIntegrityViolationException violation) {
            if (!isNameKeyConflict(violation)) {
                throw violation;
            }
            ReberuException duplicated = new ReberuException(ErrorCode.INVALID_REQUEST,
                    List.of(new FieldViolation("name", FieldReason.DUPLICATE)));
            duplicated.initCause(violation);
            throw duplicated;
        }
    }

    static boolean isNameKeyConflict(DataIntegrityViolationException violation) {
        return UniqueViolations.of(violation, NAME_KEY_CONSTRAINT);
    }

    private JobTypeView view(JobType jobType) {
        long teams = jdbc.sql("SELECT COUNT(*) FROM team WHERE org_id = :org AND job_type_id = :id")
                .param("org", jobType.orgId().toString())
                .param("id", jobType.id().toString())
                .query(Long.class)
                .single();
        return new JobTypeView(jobType.id(), jobType.name(), jobType.description(), jobType.hidden(),
                jsonMapper.readValue(jobType.defaults(), JobTypeDefaults.class), teams, jobType.version());
    }

    private List<FieldViolation> checkDefaults(JobTypeDefaults defaults) {
        List<FieldViolation> violations = new ArrayList<>();
        checkList(violations, "defaults.modules", defaults.modules(), MODULES_MAX, value ->
                MODULE_KEY.matcher(value).matches() ? null : FieldReason.INVALID_FORMAT);
        checkList(violations, "defaults.taskLabels", defaults.taskLabels(), LABELS_MAX, value -> textReason(value,
                LABEL_MAX));
        checkList(violations, "defaults.noticeTitles", defaults.noticeTitles(), NOTICE_TITLES_MAX, value ->
                textReason(value, NOTICE_TITLE_MAX));
        if (violations.stream().noneMatch(violation -> violation.field().equals("defaults.noticeTitles"))
                && jsonMapper.writeValueAsString(strip(defaults.noticeTitles())).length() > Team.NOTICE_TITLES_MAX) {
            violations.add(new FieldViolation("defaults.noticeTitles", FieldReason.TOO_LONG));
        }
        if (defaults.reportWeekStart() < 1 || defaults.reportWeekStart() > 7) {
            violations.add(new FieldViolation("defaults.reportWeekStart", FieldReason.NOT_ALLOWED_VALUE));
        }
        return violations;
    }

    private FieldReason textReason(String value, int max) {
        FieldViolation violation = nameRules.checkName("item", value, 1, max);
        return violation == null ? null : violation.reason();
    }

    static void checkList(List<FieldViolation> violations, String field, List<String> values, int max,
            Function<String, FieldReason> check) {
        if (values == null) {
            violations.add(new FieldViolation(field, FieldReason.REQUIRED));
            return;
        }
        if (values.size() > max) {
            violations.add(new FieldViolation(field, FieldReason.TOO_LONG));
            return;
        }
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            FieldReason reason = value == null ? FieldReason.REQUIRED : check.apply(value);
            if (reason == null && !seen.add(NameRules.nameKey(value))) {
                reason = FieldReason.DUPLICATE;
            }
            if (reason != null) {
                violations.add(new FieldViolation(field, reason));
                return;
            }
        }
    }

    private static JobTypeDefaults normalize(JobTypeDefaults defaults) {
        return new JobTypeDefaults(List.copyOf(defaults.modules()), strip(defaults.taskLabels()),
                strip(defaults.noticeTitles()), defaults.reportWeekStart());
    }

    private static List<String> strip(List<String> values) {
        return values.stream().map(String::strip).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static void add(List<FieldViolation> violations, FieldViolation violation) {
        if (violation != null) {
            violations.add(violation);
        }
    }

    private static ReberuException notFound() {
        return new ReberuException(ErrorCode.NOT_FOUND);
    }

    public record NewJobType(String name, String description, JobTypeDefaults defaults) {
    }

    public record JobTypeChange(Integer version, String name, String description, JobTypeDefaults defaults) {
    }

    public record JobTypeView(UUID id, String name, String description, boolean hidden, JobTypeDefaults defaults,
            long teamCount, int version) {
    }

}
