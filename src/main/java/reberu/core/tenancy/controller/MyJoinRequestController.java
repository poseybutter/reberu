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
import reberu.core.tenancy.service.JoinRequestService;
import reberu.core.tenancy.service.JoinRequestService.MyRequest;

@RestController
@RequestMapping("/api/me")
class MyJoinRequestController {

    private final JoinRequestService joinRequests;

    MyJoinRequestController(JoinRequestService joinRequests) {
        this.joinRequests = joinRequests;
    }

    @GetMapping("/join-requests")
    @Access(AccessLevel.ACCOUNT)
    MyRequests list(@AuthenticationPrincipal UUID accountId) {
        return new MyRequests(joinRequests.listMine(accountId));
    }

    @PostMapping("/org-requests/{requestOrgId}/cancel")
    @Access(AccessLevel.ACCOUNT)
    ResponseEntity<Void> cancelOrg(@AuthenticationPrincipal UUID accountId, @PathVariable UUID requestOrgId) {
        joinRequests.cancelOrg(accountId, requestOrgId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/team-requests/{requestTeamId}/cancel")
    @Access(AccessLevel.ACCOUNT)
    ResponseEntity<Void> cancelTeam(@AuthenticationPrincipal UUID accountId, @PathVariable UUID requestTeamId) {
        joinRequests.cancelTeam(accountId, requestTeamId);
        return ResponseEntity.noContent().build();
    }

    record MyRequests(List<MyRequest> items) {
    }

}
