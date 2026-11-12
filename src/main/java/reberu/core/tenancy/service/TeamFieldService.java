package reberu.core.tenancy.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.audit.ActivityEntry;
import reberu.core.audit.ActivityLogs;
import reberu.core.support.ErrorCode;
import reberu.core.support.FieldReason;
import reberu.core.support.FieldViolation;
import reberu.core.support.ReberuException;
import reberu.core.support.Uuids;
import reberu.core.tenancy.entity.TeamCustomField;
import reberu.core.tenancy.entity.TeamTaskLabel;
import reberu.core.tenancy.entity.TenancyCodes.CustomFieldKind;
import reberu.core.tenancy.entity.TenancyCodes.CustomFieldTarget;
import reberu.core.tenancy.repository.TeamCustomFieldRepository;
import reberu.core.tenancy.repository.TeamTaskLabelRepository;

public class TeamFieldService {

    public static final int LABELS_PER_TEAM_MAX = 50;
    public static final int FIELDS_PER_TARGET_MAX = 20;
    public static final int SORT_ORDER_MAX = 9999;

    private static final String LABEL_CONSTRAINT = "team_task_label_team_id_name_key_key";
    private static final String FIELD_CONSTRAINT = "team_custom_field_team_id_target_label_key_key";

    private final StateLocks locks;
    private final TeamTaskLabelRepository labels;
    private final TeamCustomFieldRepository fields;
    private final NameRules nameRules;
    private final ActivityLogs activityLogs;
    private final Clock clock;

    TeamFieldService(StateLocks locks, TeamTaskLabelRepository labels, TeamCustomFieldRepository fields,
            NameRules nameRules, ActivityLogs activityLogs, Clock clock) {
        this.locks = locks;
        this.labels = labels;
        this.fields = fields;
        this.nameRules = nameRules;
        this.activityLogs = activityLogs;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<LabelView> labels(UUID teamId, boolean includeHidden) {
        return labels.findByTeamId(teamId).stream()
                .filter(label -> includeHidden || !label.hidden())
                .map(TeamFieldService::view)
                .toList();
    }

    @Transactional
    public UUID createLabel(UUID orgId, UUID teamId, UUID actorId, String name) {
        locks.activeTeam(orgId, teamId);
        FieldViolation invalid = nameRules.checkName("name", name, 1, TeamTaskLabel.NAME_MAX);
        String nameKey = invalid == null ? NameRules.nameKey(name) : null;
        if (invalid == null && labels.existsByTeamIdAndNameKey(teamId, nameKey)) {
            invalid = new FieldViolation("name", FieldReason.DUPLICATE);
        }
        if (invalid != null) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST, List.of(invalid));
        }
        if (labels.countByTeamId(teamId) >= LABELS_PER_TEAM_MAX) {
            throw new ReberuException(ErrorCode.LIMIT_EXCEEDED);
        }
        TeamTaskLabel label = TeamTaskLabel.create(Uuids.v7(), teamId, name.strip(), nameKey,
                labels.findMaxSortOrder(teamId) + 1, clock.instant());
        saveLabel(label);
        activityLogs.recordTeam(teamId, new ActivityEntry(actorId, "TEAM_LABEL_CREATED", "TEAM_LABEL", label.id(),
                null, null));
        return label.id();
    }

    @Transactional
    public LabelView updateLabel(UUID orgId, UUID teamId, UUID labelId, UUID actorId, LabelChange change) {
        locks.activeTeam(orgId, teamId);
        TeamTaskLabel label = labels.findForUpdate(teamId, labelId).orElseThrow(TeamFieldService::notFound);
        if (change.version() == null || change.version() != label.version()) {
            throw new ReberuException(ErrorCode.VERSION_CONFLICT);
        }
        List<FieldViolation> violations = new ArrayList<>();
        String name = label.name();
        String nameKey = label.nameKey();
        if (change.name() != null) {
            FieldViolation invalid = nameRules.checkName("name", change.name(), 1, TeamTaskLabel.NAME_MAX);
            if (invalid == null) {
                nameKey = NameRules.nameKey(change.name());
                if (!nameKey.equals(label.nameKey()) && labels.existsByTeamIdAndNameKey(teamId, nameKey)) {
                    invalid = new FieldViolation("name", FieldReason.DUPLICATE);
                }
            }
            add(violations, invalid);
            name = change.name().strip();
        }
        int sortOrder = sortOrder(violations, change.sortOrder(), label.sortOrder());
        boolean hidden = change.hidden() == null ? label.hidden() : change.hidden();
        if (!violations.isEmpty()) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST, violations);
        }
        List<String> changed = new ArrayList<>();
        if (!name.equals(label.name())) {
            changed.add("name");
        }
        if (sortOrder != label.sortOrder()) {
            changed.add("sortOrder=" + sortOrder);
        }
        if (hidden != label.hidden()) {
            changed.add("hidden=" + hidden);
        }
        if (!changed.isEmpty()) {
            label.change(name, nameKey, sortOrder, hidden, clock.instant());
            saveLabel(label);
            activityLogs.recordTeam(teamId, new ActivityEntry(actorId, "TEAM_LABEL_CHANGED", "TEAM_LABEL", labelId,
                    null, String.join(";", changed)));
        }
        return view(label);
    }

    @Transactional(readOnly = true)
    public List<FieldView> fields(UUID teamId, boolean includeHidden) {
        return fields.findByTeamId(teamId).stream()
                .filter(field -> includeHidden || !field.hidden())
                .map(TeamFieldService::view)
                .toList();
    }

    @Transactional
    public UUID createField(UUID orgId, UUID teamId, UUID actorId, NewField input) {
        locks.activeTeam(orgId, teamId);
        List<FieldViolation> violations = new ArrayList<>();
        if (input.target() == null) {
            violations.add(new FieldViolation("target", FieldReason.REQUIRED));
        }
        if (input.kind() == null) {
            violations.add(new FieldViolation("kind", FieldReason.REQUIRED));
        }
        FieldViolation invalid = nameRules.checkName("label", input.label(), 1, TeamCustomField.LABEL_MAX);
        String labelKey = invalid == null ? NameRules.nameKey(input.label()) : null;
        if (invalid == null && input.target() != null
                && fields.existsByTeamIdAndTargetAndLabelKey(teamId, input.target(), labelKey)) {
            invalid = new FieldViolation("label", FieldReason.DUPLICATE);
        }
        add(violations, invalid);
        if (!violations.isEmpty()) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST, violations);
        }
        if (fields.countByTeamIdAndTarget(teamId, input.target()) >= FIELDS_PER_TARGET_MAX) {
            throw new ReberuException(ErrorCode.LIMIT_EXCEEDED);
        }
        TeamCustomField field = TeamCustomField.create(Uuids.v7(), teamId, input.target(), input.label().strip(),
                labelKey, input.kind(), Boolean.TRUE.equals(input.required()),
                fields.findMaxSortOrder(teamId, input.target()) + 1, clock.instant());
        saveField(field);
        activityLogs.recordTeam(teamId, new ActivityEntry(actorId, "TEAM_FIELD_CREATED", "TEAM_FIELD", field.id(),
                null, "target=" + field.target() + ";kind=" + field.kind()));
        return field.id();
    }

    @Transactional
    public FieldView updateField(UUID orgId, UUID teamId, UUID fieldId, UUID actorId, FieldChange change) {
        locks.activeTeam(orgId, teamId);
        TeamCustomField field = fields.findForUpdate(teamId, fieldId).orElseThrow(TeamFieldService::notFound);
        if (change.version() == null || change.version() != field.version()) {
            throw new ReberuException(ErrorCode.VERSION_CONFLICT);
        }
        List<FieldViolation> violations = new ArrayList<>();
        String label = field.label();
        String labelKey = field.labelKey();
        if (change.label() != null) {
            FieldViolation invalid = nameRules.checkName("label", change.label(), 1, TeamCustomField.LABEL_MAX);
            if (invalid == null) {
                labelKey = NameRules.nameKey(change.label());
                if (!labelKey.equals(field.labelKey())
                        && fields.existsByTeamIdAndTargetAndLabelKey(teamId, field.target(), labelKey)) {
                    invalid = new FieldViolation("label", FieldReason.DUPLICATE);
                }
            }
            add(violations, invalid);
            label = change.label().strip();
        }
        boolean required = change.required() == null ? field.required() : change.required();
        int sortOrder = sortOrder(violations, change.sortOrder(), field.sortOrder());
        boolean hidden = change.hidden() == null ? field.hidden() : change.hidden();
        if (!violations.isEmpty()) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST, violations);
        }
        List<String> changed = new ArrayList<>();
        if (!label.equals(field.label())) {
            changed.add("label");
        }
        if (required != field.required()) {
            changed.add("required=" + required);
        }
        if (sortOrder != field.sortOrder()) {
            changed.add("sortOrder=" + sortOrder);
        }
        if (hidden != field.hidden()) {
            changed.add("hidden=" + hidden);
        }
        if (!changed.isEmpty()) {
            field.change(label, labelKey, required, sortOrder, hidden, clock.instant());
            saveField(field);
            activityLogs.recordTeam(teamId, new ActivityEntry(actorId, "TEAM_FIELD_CHANGED", "TEAM_FIELD", fieldId,
                    null, String.join(";", changed)));
        }
        return view(field);
    }

    void copyLabels(UUID teamId, List<String> names, Instant at) {
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i).strip();
            labels.save(TeamTaskLabel.create(Uuids.v7(), teamId, name, NameRules.nameKey(name), i, at));
        }
    }

    private void saveLabel(TeamTaskLabel label) {
        try {
            labels.saveAndFlush(label);
        }
        catch (DataIntegrityViolationException violation) {
            throw duplicate(violation, LABEL_CONSTRAINT, "name");
        }
    }

    private void saveField(TeamCustomField field) {
        try {
            fields.saveAndFlush(field);
        }
        catch (DataIntegrityViolationException violation) {
            throw duplicate(violation, FIELD_CONSTRAINT, "label");
        }
    }

    private static RuntimeException duplicate(DataIntegrityViolationException violation, String constraint,
            String field) {
        if (!UniqueViolations.of(violation, constraint)) {
            return violation;
        }
        ReberuException duplicated = new ReberuException(ErrorCode.INVALID_REQUEST,
                List.of(new FieldViolation(field, FieldReason.DUPLICATE)));
        duplicated.initCause(violation);
        return duplicated;
    }

    private static int sortOrder(List<FieldViolation> violations, Integer value, int current) {
        if (value == null) {
            return current;
        }
        if (value < 0 || value > SORT_ORDER_MAX) {
            violations.add(new FieldViolation("sortOrder", FieldReason.NOT_ALLOWED_VALUE));
        }
        return value;
    }

    private static void add(List<FieldViolation> violations, FieldViolation violation) {
        if (violation != null) {
            violations.add(violation);
        }
    }

    private static ReberuException notFound() {
        return new ReberuException(ErrorCode.NOT_FOUND);
    }

    private static LabelView view(TeamTaskLabel label) {
        return new LabelView(label.id(), label.name(), label.sortOrder(), label.hidden(), label.version());
    }

    private static FieldView view(TeamCustomField field) {
        return new FieldView(field.id(), field.target(), field.label(), field.kind(), field.required(),
                field.sortOrder(), field.hidden(), field.version());
    }

    public record LabelChange(Integer version, String name, Integer sortOrder, Boolean hidden) {
    }

    public record NewField(CustomFieldTarget target, String label, CustomFieldKind kind, Boolean required) {
    }

    public record FieldChange(Integer version, String label, Boolean required, Integer sortOrder, Boolean hidden) {
    }

    public record LabelView(UUID id, String name, int sortOrder, boolean hidden, int version) {
    }

    public record FieldView(UUID id, CustomFieldTarget target, String label, CustomFieldKind kind, boolean required,
            int sortOrder, boolean hidden, int version) {
    }

}
