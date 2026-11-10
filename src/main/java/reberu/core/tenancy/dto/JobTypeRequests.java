package reberu.core.tenancy.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import reberu.core.tenancy.service.DefaultJobTypes.JobTypeDefaults;
import reberu.core.tenancy.service.JobTypeService;
import reberu.core.tenancy.service.JobTypeService.JobTypeChange;
import reberu.core.tenancy.service.JobTypeService.NewJobType;

public final class JobTypeRequests {

    private JobTypeRequests() {
    }

    public record Create(
            @NotNull @Size(max = JobTypeService.NAME_MAX) String name,
            @Size(max = JobTypeService.DESCRIPTION_MAX) String description,
            @NotNull Defaults defaults) {

        public NewJobType toNewJobType() {
            return new NewJobType(name, description, defaults.toDefaults());
        }

    }

    public record Update(
            @NotNull Integer version,
            @Size(max = JobTypeService.NAME_MAX) String name,
            @Size(max = JobTypeService.DESCRIPTION_MAX) String description,
            Defaults defaults) {

        public JobTypeChange toChange() {
            return new JobTypeChange(version, name, description, defaults == null ? null : defaults.toDefaults());
        }

    }

    public record Defaults(
            @NotNull List<String> modules,
            @NotNull List<String> taskLabels,
            @NotNull List<String> noticeTitles,
            @NotNull Integer reportWeekStart) {

        JobTypeDefaults toDefaults() {
            return new JobTypeDefaults(modules, taskLabels, noticeTitles, reportWeekStart);
        }

    }

    public record Created(UUID jobTypeId) {
    }

}
