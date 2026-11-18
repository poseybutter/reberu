package reberu.core.tenancy.dto;

import reberu.core.tenancy.entity.TenancyCodes.TeamRole;
import reberu.core.tenancy.service.InviteLinkService.NewLink;

public final class InviteLinkRequests {

    private InviteLinkRequests() {
    }

    public record Create(Boolean approvalRequired, TeamRole role, Integer expiresInDays, Integer maxUses) {

        public NewLink toNewLink() {
            return new NewLink(approvalRequired, role, expiresInDays, maxUses);
        }

    }

}
