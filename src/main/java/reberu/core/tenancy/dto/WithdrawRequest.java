package reberu.core.tenancy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import reberu.core.tenancy.entity.Organization;
import reberu.core.tenancy.service.WithdrawalService.OrgName;

public record WithdrawRequest(
        @Size(max = 20) String confirmText,
        List<@Valid OrgNameConfirmation> orgNameConfirmations) {

    public List<OrgName> toOrgNames() {
        return orgNameConfirmations == null ? null : orgNameConfirmations.stream()
                .map(org -> org == null ? null : new OrgName(org.orgId(), org.name()))
                .toList();
    }

    public record OrgNameConfirmation(UUID orgId, @Size(max = Organization.NAME_MAX) String name) {
    }

}
