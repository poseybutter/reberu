package reberu.core.tenancy.dto;

import jakarta.validation.constraints.Size;
import reberu.core.tenancy.service.OperatorService;

public record SuspendOrgRequest(@Size(max = OperatorService.REASON_MAX) String reason) {
}
