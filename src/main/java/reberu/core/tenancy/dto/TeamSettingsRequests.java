package reberu.core.tenancy.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import reberu.core.tenancy.entity.Team;
import reberu.core.tenancy.entity.TeamCustomField;
import reberu.core.tenancy.entity.TeamTaskLabel;
import reberu.core.tenancy.entity.TenancyCodes.CustomFieldKind;
import reberu.core.tenancy.entity.TenancyCodes.CustomFieldTarget;
import reberu.core.tenancy.entity.TenancyCodes.TeamJoinMode;
import reberu.core.tenancy.entity.TenancyCodes.Visibility;
import reberu.core.tenancy.service.TeamFieldService.FieldChange;
import reberu.core.tenancy.service.TeamFieldService.LabelChange;
import reberu.core.tenancy.service.TeamFieldService.NewField;
import reberu.core.tenancy.service.TeamSettingsService.SettingsChange;

public final class TeamSettingsRequests {

    private TeamSettingsRequests() {
    }

    public record UpdateSettings(
            @NotNull Integer version,
            @Size(max = Team.NAME_MAX) String name,
            Visibility visibility,
            TeamJoinMode joinMode,
            Integer reportWeekStart,
            List<String> noticeTitles) {

        public SettingsChange toChange() {
            return new SettingsChange(version, name, visibility, joinMode, reportWeekStart, noticeTitles);
        }

    }

    public record CreateLabel(@NotNull @Size(max = TeamTaskLabel.NAME_MAX) String name) {
    }

    public record UpdateLabel(
            @NotNull Integer version,
            @Size(max = TeamTaskLabel.NAME_MAX) String name,
            Integer sortOrder,
            Boolean hidden) {

        public LabelChange toChange() {
            return new LabelChange(version, name, sortOrder, hidden);
        }

    }

    public record CreateField(
            @NotNull CustomFieldTarget target,
            @NotNull @Size(max = TeamCustomField.LABEL_MAX) String label,
            @NotNull CustomFieldKind kind,
            Boolean required) {

        public NewField toNewField() {
            return new NewField(target, label, kind, required);
        }

    }

    public record UpdateField(
            @NotNull Integer version,
            @Size(max = TeamCustomField.LABEL_MAX) String label,
            Boolean required,
            Integer sortOrder,
            Boolean hidden) {

        public FieldChange toChange() {
            return new FieldChange(version, label, required, sortOrder, hidden);
        }

    }

    public record LabelCreated(UUID labelId) {
    }

    public record FieldCreated(UUID fieldId) {
    }

}
