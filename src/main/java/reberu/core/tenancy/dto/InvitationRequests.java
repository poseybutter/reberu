package reberu.core.tenancy.dto;

import java.util.List;
import java.util.UUID;
import reberu.core.tenancy.entity.TenancyCodes.MembershipKind;
import reberu.core.tenancy.entity.TenancyCodes.TeamRole;
import reberu.core.tenancy.service.InvitationService.Created;
import reberu.core.tenancy.service.InvitationService.NewInvitations;

public final class InvitationRequests {

    private InvitationRequests() {
    }

    public record CreateForOrg(List<String> emails, MembershipKind kind, UUID teamId, TeamRole role,
            Integer guestDays) {

        public NewInvitations toNewInvitations() {
            return new NewInvitations(emails, kind, role, guestDays);
        }

    }

    public record CreateForTeam(List<String> emails, MembershipKind kind, TeamRole role, Integer guestDays) {

        public NewInvitations toNewInvitations() {
            return new NewInvitations(emails, kind, role, guestDays);
        }

    }

    public record CreatedList(List<Created> invitations) {
    }

}
