package reberu.core.tenancy.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.support.ErrorCode;
import reberu.core.support.FailureStreaks;
import reberu.core.support.RateLimit;
import reberu.core.support.RateLimited;
import reberu.core.support.RateLimits;
import reberu.core.support.ReberuException;
import reberu.core.tenancy.HomeScreens.HomeNext;
import reberu.core.tenancy.HomeScreens.HomeNext.Screen;
import reberu.core.tenancy.service.InviteLinkService;
import reberu.core.tenancy.service.InviteLinkService.Outcome;
import reberu.core.tenancy.service.InviteLinkService.Redemption;

@RestController
class InviteLinkRedeemController {

    private final InviteLinkService links;
    private final FailureStreaks failureStreaks;
    private final Duration failureDelay;

    InviteLinkRedeemController(InviteLinkService links, FailureStreaks failureStreaks,
            @Value("${reberu.tenancy.invite-link-failure-delay}") Duration failureDelay) {
        this.links = links;
        this.failureStreaks = failureStreaks;
        this.failureDelay = failureDelay;
    }

    @PostMapping("/api/invite-links/redeem")
    @RateLimited(RateLimit.INVITE_LINK)
    @Access(AccessLevel.ACCOUNT)
    Redeemed redeem(@AuthenticationPrincipal UUID accountId, @RequestBody RedeemRequest body,
            HttpServletRequest request) {
        long started = System.nanoTime();
        String key = RateLimits.ipKey(request.getRemoteAddr());
        failureStreaks.check(RateLimit.INVITE_LINK, key);
        Redemption redemption = links.redeem(accountId, body.token());
        if (redemption.failed()) {
            failureStreaks.failed(RateLimit.INVITE_LINK, key);
            waitUntil(started + failureDelay.toNanos());
            throw new ReberuException(ErrorCode.NOT_FOUND);
        }
        failureStreaks.succeeded(RateLimit.INVITE_LINK, key);
        if (redemption.outcome() == Outcome.REQUESTED) {
            return new Redeemed(Outcome.REQUESTED, new HomeNext(Screen.JOIN_PENDING, null), null, null);
        }
        return new Redeemed(redemption.outcome(), new HomeNext(Screen.TEAM_HOME, redemption.teamId()),
                redemption.orgName(), redemption.teamName());
    }

    private static void waitUntil(long deadline) {
        long remaining = deadline - System.nanoTime();
        while (remaining > 0) {
            try {
                Thread.sleep(remaining / 1_000_000, (int) (remaining % 1_000_000));
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            remaining = deadline - System.nanoTime();
        }
    }

    record RedeemRequest(String token) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Redeemed(Outcome result, HomeNext next, String orgName, String teamName) {
    }

}
