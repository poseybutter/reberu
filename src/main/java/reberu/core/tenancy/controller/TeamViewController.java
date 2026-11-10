package reberu.core.tenancy.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.access.TeamContext;
import reberu.core.tenancy.service.TeamViewService;
import reberu.core.tenancy.service.TeamViewService.TeamView;

@RestController
class TeamViewController {

    private final TeamViewService teamViews;

    TeamViewController(TeamViewService teamViews) {
        this.teamViews = teamViews;
    }

    @GetMapping("/api/teams/{teamId}")
    @Access(AccessLevel.TEAM_VIEWER)
    TeamView view(TeamContext context) {
        return teamViews.view(context);
    }

}
