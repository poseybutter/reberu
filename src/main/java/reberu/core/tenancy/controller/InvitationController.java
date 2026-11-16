package reberu.core.tenancy.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.access.OrgContext;
import reberu.core.support.CursorPage;
import reberu.core.support.IdempotentRequest;
import reberu.core.support.IdempotentRequests;
import reberu.core.support.RateLimit;
import reberu.core.support.RateLimited;
import reberu.core.support.RequiresRecentAuth;
import reberu.core.tenancy.dto.InvitationRequests;
import reberu.core.tenancy.entity.TenancyCodes.InvitationState;
import reberu.core.tenancy.entity.TenancyCodes.MembershipKind;
import reberu.core.tenancy.service.InvitationService;
import reberu.core.tenancy.service.InvitationService.InvitationFilter;
import reberu.core.tenancy.service.InvitationService.InvitationView;

@Validated
@RestController
@RequestMapping("/api/orgs/{orgId}/invitations")
class InvitationController {

    private final InvitationService invitations;
    private final IdempotentRequests idempotentRequests;

    InvitationController(InvitationService invitations, IdempotentRequests idempotentRequests) {
        this.invitations = invitations;
        this.idempotentRequests = idempotentRequests;
    }

    @GetMapping
    @Access(AccessLevel.ORG_ADMIN)
    CursorPage<InvitationView> list(OrgContext org, @RequestParam(required = false) InvitationState state,
            @RequestParam(required = false) MembershipKind kind, @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + InvitationService.DEFAULT_LIMIT) @Min(1)
            @Max(InvitationService.MAX_LIMIT) int limit) {
        return invitations.listForOrg(org.orgId(), new InvitationFilter(state, kind, teamId), cursor, limit);
    }

    @PostMapping
    @RateLimited(RateLimit.INVITATION)
    @Access(value = AccessLevel.ORG_ADMIN, write = true)
    ResponseEntity<String> create(OrgContext org,
            @RequestHeader(name = IdempotentRequest.HEADER, required = false) String key,
            @RequestBody InvitationRequests.CreateForOrg body, HttpServletRequest request) {
        IdempotentRequest idempotent = IdempotentRequest.of(org.accountId(), key, request, body);
        return idempotentRequests.execute(idempotent, () -> ResponseEntity.status(HttpStatus.CREATED)
                .body(new InvitationRequests.CreatedList(invitations.createForOrg(org.orgId(), org.accountId(),
                        body.teamId(), body.toNewInvitations()))));
    }

    @PostMapping("/{invitationId}/approve")
    @RequiresRecentAuth
    @Access(value = AccessLevel.ORG_ADMIN, write = true)
    ResponseEntity<Void> approve(OrgContext org, @PathVariable UUID invitationId) {
        invitations.approve(org.orgId(), org.accountId(), invitationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{invitationId}/reject")
    @RequiresRecentAuth
    @Access(value = AccessLevel.ORG_ADMIN, write = true)
    ResponseEntity<Void> reject(OrgContext org, @PathVariable UUID invitationId) {
        invitations.reject(org.orgId(), org.accountId(), invitationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{invitationId}/revoke")
    @Access(value = AccessLevel.ORG_ADMIN, write = true)
    ResponseEntity<Void> revoke(OrgContext org, @PathVariable UUID invitationId) {
        invitations.revokeForOrg(org.orgId(), org.accountId(), invitationId);
        return ResponseEntity.noContent().build();
    }

}
