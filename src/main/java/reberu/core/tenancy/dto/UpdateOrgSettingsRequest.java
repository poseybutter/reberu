package reberu.core.tenancy.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import reberu.core.tenancy.entity.Organization;
import reberu.core.tenancy.entity.TenancyCodes.JoinRequestMode;
import reberu.core.tenancy.entity.TenancyCodes.Visibility;
import reberu.core.tenancy.service.OrgSettingsService.SettingsChange;

public record UpdateOrgSettingsRequest(
        @NotNull Integer version,
        @Size(max = Organization.NAME_MAX) String name,
        @Size(max = Organization.TAGLINE_MAX) String tagline,
        @Size(max = Organization.TIME_ZONE_MAX) String timeZone,
        Visibility visibility,
        Boolean joinLinkEnabled,
        JoinRequestMode joinRequestMode,
        Boolean memberAdmissionReview,
        Boolean guestApprovalRequired,
        Integer guestDefaultDays,
        Integer guestMaxDays,
        Boolean guestOrgspaceAllowed,
        Integer invitationTtlDays) {

    public SettingsChange toChange() {
        return new SettingsChange(version, name, tagline, timeZone, visibility, joinLinkEnabled, joinRequestMode,
                memberAdmissionReview, guestApprovalRequired, guestDefaultDays, guestMaxDays, guestOrgspaceAllowed,
                invitationTtlDays);
    }

}
