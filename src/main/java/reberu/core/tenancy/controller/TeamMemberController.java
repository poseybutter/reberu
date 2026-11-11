package reberu.core.tenancy.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.access.MembershipDirectory.Role;
import reberu.core.access.TeamContext;
import reberu.core.support.CursorPage;
import reberu.core.support.RequiresRecentAuth;
import reberu.core.tenancy.dto.TeamMemberRequests;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;
import reberu.core.tenancy.service.TeamMemberService;
import reberu.core.tenancy.service.TeamMemberService.ActivityFilter;
import reberu.core.tenancy.service.TeamMemberService.TeamActivityView;
import reberu.core.tenancy.service.TeamMemberService.TeamMemberView;

@Validated
@RestController
@RequestMapping("/api/teams/{teamId}")
class TeamMemberController {

    private final TeamMemberService members;

    TeamMemberController(TeamMemberService members) {
        this.members = members;
    }

    @GetMapping("/members")
    @Access(AccessLevel.TEAM_VIEWER)
    CursorPage<TeamMemberView> list(TeamContext team, @RequestParam(required = false) MembershipState state,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + TeamMemberService.DEFAULT_LIMIT) @Min(1)
            @Max(TeamMemberService.MAX_LIMIT) int limit) {
        return members.list(team.orgId(), team.teamId(), team.role() == Role.TEAM_ADMIN || team.orgAdmin(), state,
                cursor, limit);
    }

    @PostMapping("/members")
    @Access(value = AccessLevel.TEAM_ADMIN, write = true)
    ResponseEntity<TeamMemberRequests.Added> add(TeamContext team, @Valid @RequestBody TeamMemberRequests.Add body) {
        members.add(team.orgId(), team.teamId(), team.accountId(), body.accountId(), body.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(new TeamMemberRequests.Added(body.accountId()));
    }

    @PutMapping("/members/{accountId}/role")
    @RequiresRecentAuth
    @Access(value = AccessLevel.TEAM_ADMIN, write = true)
    ResponseEntity<Void> changeRole(TeamContext team, @PathVariable UUID accountId,
            @Valid @RequestBody TeamMemberRequests.ChangeRole body) {
        members.changeRole(team.orgId(), team.teamId(), team.accountId(), accountId, body.role(), body.version());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/members/{accountId}/suspend")
    @RequiresRecentAuth
    @Access(value = AccessLevel.TEAM_ADMIN, write = true)
    ResponseEntity<Void> suspend(TeamContext team, @PathVariable UUID accountId) {
        members.suspend(team.orgId(), team.teamId(), team.accountId(), accountId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/members/{accountId}/unsuspend")
    @RequiresRecentAuth
    @Access(value = AccessLevel.TEAM_ADMIN, write = true)
    ResponseEntity<Void> unsuspend(TeamContext team, @PathVariable UUID accountId) {
        members.unsuspend(team.orgId(), team.teamId(), team.accountId(), accountId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/members/{accountId}/remove")
    @RequiresRecentAuth
    @Access(value = AccessLevel.TEAM_ADMIN, write = true)
    ResponseEntity<Void> remove(TeamContext team, @PathVariable UUID accountId) {
        members.remove(team.orgId(), team.teamId(), team.accountId(), accountId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/activity")
    @Access(AccessLevel.TEAM_ADMIN)
    CursorPage<TeamActivityView> activity(TeamContext team, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + TeamMemberService.DEFAULT_LIMIT) @Min(1)
            @Max(TeamMemberService.MAX_LIMIT) int limit) {
        return members.activity(team.teamId(), new ActivityFilter(from, to, actorId, action), cursor, limit);
    }

}
