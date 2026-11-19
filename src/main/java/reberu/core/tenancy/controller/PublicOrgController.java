package reberu.core.tenancy.controller;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.support.RateLimit;
import reberu.core.support.RateLimited;
import reberu.core.tenancy.HomeScreens.HomeNext;
import reberu.core.tenancy.HomeScreens.HomeNext.Screen;
import reberu.core.tenancy.service.JoinRequestService;
import reberu.core.tenancy.service.JoinRequestService.Outcome;
import reberu.core.tenancy.service.JoinRequestService.PublicOrg;

@RestController
@RequestMapping("/api/public-orgs")
@ConditionalOnExpression("!'${reberu.tenancy.mode:}'.trim().equalsIgnoreCase('SINGLE_ORG')")
class PublicOrgController {

    private final JoinRequestService joinRequests;

    PublicOrgController(JoinRequestService joinRequests) {
        this.joinRequests = joinRequests;
    }

    @GetMapping
    @Access(AccessLevel.ACCOUNT)
    @RateLimited(RateLimit.PUBLIC_ORG_SEARCH)
    PublicOrgs search(@RequestParam(required = false) String q) {
        return new PublicOrgs(joinRequests.search(q));
    }

    @PostMapping("/{publicOrgId}/join")
    @Access(AccessLevel.ACCOUNT)
    @RateLimited(RateLimit.JOIN_REQUEST)
    Joined join(@AuthenticationPrincipal UUID accountId, @PathVariable UUID publicOrgId) {
        Outcome outcome = joinRequests.joinOrg(accountId, publicOrgId);
        return new Joined(outcome, new HomeNext(outcome == Outcome.JOINED ? Screen.TEAM_PICKER : Screen.JOIN_PENDING,
                null));
    }

    record PublicOrgs(List<PublicOrg> items) {
    }

    record Joined(Outcome result, HomeNext next) {
    }

}
