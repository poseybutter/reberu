package reberu.core.tenancy.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.support.CursorPage;
import reberu.core.support.IdempotentRequest;
import reberu.core.support.IdempotentRequests;
import reberu.core.support.RequiresRecentAuth;
import reberu.core.tenancy.dto.CreateTeamRequest;
import reberu.core.tenancy.dto.CreateTeamResponse;
import reberu.core.tenancy.dto.RestoreTeamRequest;
import reberu.core.tenancy.service.TeamService;
import reberu.core.tenancy.service.TeamService.TeamSummary;

@RestController
@Validated
@RequestMapping("/api/orgs/{orgId}/teams")
class TeamController {

    private final TeamService teams;
    private final IdempotentRequests idempotentRequests;

    TeamController(TeamService teams, IdempotentRequests idempotentRequests) {
        this.teams = teams;
        this.idempotentRequests = idempotentRequests;
    }

    @GetMapping
    CursorPage<TeamSummary> list(@AuthenticationPrincipal UUID accountId, @PathVariable UUID orgId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + TeamService.DEFAULT_LIMIT) @Min(1) @Max(TeamService.MAX_LIMIT) int limit) {
        return teams.list(orgId, accountId, cursor, limit);
    }

    @PostMapping
    ResponseEntity<String> create(@AuthenticationPrincipal UUID accountId, @PathVariable UUID orgId,
            @RequestHeader(name = IdempotentRequest.HEADER, required = false) String key,
            @Valid @RequestBody CreateTeamRequest body, HttpServletRequest request) {
        IdempotentRequest idempotent = IdempotentRequest.of(accountId, key, request, body);
        return idempotentRequests.execute(idempotent, () -> ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateTeamResponse(teams.create(orgId, accountId, body.toNewTeam()))));
    }

    @PostMapping("/{teamId}/archive")
    @RequiresRecentAuth
    ResponseEntity<Void> archive(@AuthenticationPrincipal UUID accountId, @PathVariable UUID orgId,
            @PathVariable UUID teamId) {
        teams.archive(orgId, teamId, accountId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{teamId}/restore")
    @RequiresRecentAuth
    ResponseEntity<Void> restore(@AuthenticationPrincipal UUID accountId, @PathVariable UUID orgId,
            @PathVariable UUID teamId, @RequestBody(required = false) RestoreTeamRequest body) {
        teams.restore(orgId, teamId, accountId, body == null ? null : body.newAdminAccountId());
        return ResponseEntity.noContent().build();
    }

}
