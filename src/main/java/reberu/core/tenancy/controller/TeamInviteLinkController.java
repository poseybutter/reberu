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
import reberu.core.tenancy.dto.InviteLinkRequests;
import reberu.core.tenancy.service.InviteLinkService;
import reberu.core.tenancy.service.InviteLinkService.LinkView;

@Validated
@RestController
@RequestMapping("/api/teams/{teamId}/invite-links")
class TeamInviteLinkController {

    private final InviteLinkService links;
    private final IdempotentRequests idempotentRequests;

    TeamInviteLinkController(InviteLinkService links, IdempotentRequests idempotentRequests) {
        this.links = links;
        this.idempotentRequests = idempotentRequests;
    }

    @GetMapping
    @Access(AccessLevel.TEAM_ADMIN)
    CursorPage<LinkView> list(TeamContext team, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + InviteLinkService.DEFAULT_LIMIT) @Min(1)
            @Max(InviteLinkService.MAX_LIMIT) int limit) {
        return links.list(team.orgId(), team.teamId(), cursor, limit);
    }

    @PostMapping
    @Access(value = AccessLevel.TEAM_ADMIN, write = true)
    ResponseEntity<String> create(TeamContext team,
            @RequestHeader(name = IdempotentRequest.HEADER, required = false) String key,
            @RequestBody InviteLinkRequests.Create body, HttpServletRequest request) {
        IdempotentRequest idempotent = IdempotentRequest.of(team.accountId(), key, request, body);
        links.requireLinksAllowed(team.orgId());
        return idempotentRequests.executeWithoutReplay(idempotent, () -> ResponseEntity.status(HttpStatus.CREATED)
                .body(links.create(team.orgId(), team.teamId(), team.accountId(), body.toNewLink())));
    }

    @PostMapping("/{linkId}/disable")
    @Access(value = AccessLevel.TEAM_ADMIN, write = true)
    ResponseEntity<Void> disable(TeamContext team, @PathVariable UUID linkId) {
        links.disable(team.orgId(), team.teamId(), team.accountId(), linkId);
        return ResponseEntity.noContent().build();
    }

}
