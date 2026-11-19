package reberu.core.tenancy.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import reberu.core.access.TeamContext;
import reberu.core.support.CursorPage;
import reberu.core.tenancy.service.JoinRequestReviewService;
import reberu.core.tenancy.service.JoinRequestReviewService.TeamRequestView;

@Validated
@RestController
@RequestMapping("/api/teams/{teamId}/join-requests")
class TeamJoinRequestController {

    private final JoinRequestReviewService reviews;

    TeamJoinRequestController(JoinRequestReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping
    @Access(AccessLevel.TEAM_ADMIN)
    CursorPage<TeamRequestView> list(TeamContext team, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + JoinRequestReviewService.DEFAULT_LIMIT) @Min(1)
            @Max(JoinRequestReviewService.MAX_LIMIT) int limit) {
        return reviews.listTeam(team.orgId(), team.teamId(), cursor, limit);
    }

    @PostMapping("/{accountId}/approve")
    @Access(value = AccessLevel.TEAM_ADMIN, write = true)
    ResponseEntity<Void> approve(TeamContext team, @PathVariable UUID accountId) {
        reviews.approveTeam(team.orgId(), team.teamId(), team.accountId(), accountId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{accountId}/reject")
    @Access(value = AccessLevel.TEAM_ADMIN, write = true)
    ResponseEntity<Void> reject(TeamContext team, @PathVariable UUID accountId) {
        reviews.rejectTeam(team.orgId(), team.teamId(), team.accountId(), accountId);
        return ResponseEntity.noContent().build();
    }

}
