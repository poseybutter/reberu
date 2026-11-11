package reberu.core.tenancy.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.access.OrgContext;
import reberu.core.support.CursorPage;
import reberu.core.support.RequiresRecentAuth;
import reberu.core.tenancy.entity.TenancyCodes.MembershipKind;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;
import reberu.core.tenancy.service.MemberService;
import reberu.core.tenancy.service.MemberService.MemberFilter;
import reberu.core.tenancy.service.MemberService.MemberView;
import reberu.core.tenancy.service.OrgActivityService;
import reberu.core.tenancy.service.OrgActivityService.ActivityFilter;
import reberu.core.tenancy.service.OrgActivityService.ActivityView;

@Validated
@RestController
@RequestMapping("/api/orgs/{orgId}")
class MemberController {

    private final MemberService members;
    private final OrgActivityService activity;

    MemberController(MemberService members, OrgActivityService activity) {
        this.members = members;
        this.activity = activity;
    }

    @GetMapping("/members")
    @Access(AccessLevel.ORG_ADMIN)
    CursorPage<MemberView> list(OrgContext org, @RequestParam(required = false) MembershipState state,
            @RequestParam(required = false) MembershipKind kind, @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + MemberService.DEFAULT_LIMIT) @Min(1) @Max(MemberService.MAX_LIMIT)
            int limit) {
        return members.list(org.orgId(), new MemberFilter(state, kind, teamId), cursor, limit);
    }

    @PostMapping("/members/{accountId}/suspend")
    @RequiresRecentAuth
    @Access(value = AccessLevel.ORG_ADMIN, write = true)
    ResponseEntity<Void> suspend(OrgContext org, @PathVariable UUID accountId) {
        members.suspend(org.orgId(), org.accountId(), accountId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/members/{accountId}/unsuspend")
    @RequiresRecentAuth
    @Access(value = AccessLevel.ORG_ADMIN, write = true)
    ResponseEntity<Void> unsuspend(OrgContext org, @PathVariable UUID accountId) {
        members.unsuspend(org.orgId(), org.accountId(), accountId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/members/{accountId}/remove")
    @RequiresRecentAuth
    @Access(value = AccessLevel.ORG_ADMIN, write = true)
    ResponseEntity<Void> remove(OrgContext org, @PathVariable UUID accountId) {
        members.remove(org.orgId(), org.accountId(), accountId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/members/{accountId}/grant-org-admin")
    @RequiresRecentAuth
    @Access(value = AccessLevel.ORG_ADMIN, write = true)
    ResponseEntity<Void> grantAdmin(OrgContext org, @PathVariable UUID accountId) {
        members.grantAdmin(org.orgId(), org.accountId(), accountId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/members/{accountId}/revoke-org-admin")
    @RequiresRecentAuth
    @Access(value = AccessLevel.ORG_ADMIN, write = true)
    ResponseEntity<Void> revokeAdmin(OrgContext org, @PathVariable UUID accountId) {
        members.revokeAdmin(org.orgId(), org.accountId(), accountId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/activity")
    @Access(AccessLevel.ORG_ADMIN)
    CursorPage<ActivityView> activity(OrgContext org, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action, @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + OrgActivityService.DEFAULT_LIMIT) @Min(1)
            @Max(OrgActivityService.MAX_LIMIT) int limit) {
        return activity.list(org.orgId(), new ActivityFilter(from, to, actorId, action, teamId), cursor, limit);
    }

}
