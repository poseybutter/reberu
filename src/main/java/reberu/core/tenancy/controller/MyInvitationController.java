package reberu.core.tenancy.controller;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.support.ErrorCode;
import reberu.core.support.ReberuException;
import reberu.core.tenancy.HomeScreens.HomeNext;
import reberu.core.tenancy.HomeScreens.HomeNext.Screen;
import reberu.core.tenancy.service.HomeService;
import reberu.core.tenancy.service.InvitationService;
import reberu.core.tenancy.service.InvitationService.Acceptance;
import reberu.core.tenancy.service.InvitationService.MyInvitation;

@RestController
@RequestMapping("/api/me/invitations")
class MyInvitationController {

    private final InvitationService invitations;
    private final HomeService home;

    MyInvitationController(InvitationService invitations, HomeService home) {
        this.invitations = invitations;
        this.home = home;
    }

    @GetMapping
    @Access(AccessLevel.ACCOUNT)
    MyInvitations list(@AuthenticationPrincipal UUID accountId) {
        return new MyInvitations(invitations.listMine(accountId));
    }

    @PostMapping("/{invitationId}/accept")
    @Access(AccessLevel.ACCOUNT)
    Accepted accept(@AuthenticationPrincipal UUID accountId, @PathVariable UUID invitationId) {
        Acceptance acceptance = invitations.accept(accountId, invitationId);
        if (!acceptance.accepted()) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        return new Accepted(acceptance.teamId() == null ? home.next(accountId)
                : new HomeNext(Screen.TEAM_HOME, acceptance.teamId()));
    }

    @PostMapping("/{invitationId}/decline")
    @Access(AccessLevel.ACCOUNT)
    ResponseEntity<Void> decline(@AuthenticationPrincipal UUID accountId, @PathVariable UUID invitationId) {
        if (!invitations.decline(accountId, invitationId)) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        return ResponseEntity.noContent().build();
    }

    record MyInvitations(List<MyInvitation> items) {
    }

    record Accepted(HomeNext next) {
    }

}
