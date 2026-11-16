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
import reberu.core.access.TeamContext;
import reberu.core.support.CursorPage;
import reberu.core.support.IdempotentRequest;
import reberu.core.support.IdempotentRequests;
import reberu.core.support.RateLimit;
import reberu.core.support.RateLimited;
import reberu.core.tenancy.dto.InvitationRequests;
import reberu.core.tenancy.entity.TenancyCodes.InvitationState;
import reberu.core.tenancy.entity.TenancyCodes.MembershipKind;
import reberu.core.tenancy.service.InvitationService;
import reberu.core.tenancy.service.InvitationService.InvitationView;

@Validated
@RestController
@RequestMapping("/api/teams/{teamId}/invitations")
class TeamInvitationController {

    private final InvitationService invitations;
    private final IdempotentRequests idempotentRequests;

    TeamInvitationController(InvitationService invitations, IdempotentRequests idempotentRequests) {
        this.invitations = invitations;
        this.idempotentRequests = idempotentRequests;
    }

    @GetMapping
    @Access(AccessLevel.TEAM_ADMIN)
    CursorPage<InvitationView> list(TeamContext team, @RequestParam(required = false) InvitationState state,
            @RequestParam(required = false) MembershipKind kind, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + InvitationService.DEFAULT_LIMIT) @Min(1)
            @Max(InvitationService.MAX_LIMIT) int limit) {
        return invitations.listForTeam(team.orgId(), team.teamId(), state, kind, cursor, limit);
    }

    @PostMapping
    @RateLimited(RateLimit.INVITATION)
    @Access(value = AccessLevel.TEAM_ADMIN, write = true)
    ResponseEntity<String> create(TeamContext team,
            @RequestHeader(name = IdempotentRequest.HEADER, required = false) String key,
            @RequestBody InvitationRequests.CreateForTeam body, HttpServletRequest request) {
        IdempotentRequest idempotent = IdempotentRequest.of(team.accountId(), key, request, body);
        return idempotentRequests.execute(idempotent, () -> ResponseEntity.status(HttpStatus.CREATED)
                .body(new InvitationRequests.CreatedList(invitations.createForTeam(team.orgId(), team.teamId(),
                        team.accountId(), body.toNewInvitations()))));
    }

    @PostMapping("/{invitationId}/revoke")
    @Access(value = AccessLevel.TEAM_ADMIN, write = true)
    ResponseEntity<Void> revoke(TeamContext team, @PathVariable UUID invitationId) {
        invitations.revokeForTeam(team.orgId(), team.teamId(), team.accountId(), invitationId);
        return ResponseEntity.noContent().build();
    }

}
