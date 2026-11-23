package reberu.core.module.controller;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.access.TeamContext;
import reberu.core.module.dto.ModuleRequests;
import reberu.core.module.service.ModuleService;
import reberu.core.module.service.ModuleService.TeamImpact;
import reberu.core.module.service.ModuleService.TeamModuleView;

@RestController
@RequestMapping("/api/teams/{teamId}/modules")
class TeamModuleController {

    private final ModuleService modules;

    TeamModuleController(ModuleService modules) {
        this.modules = modules;
    }

    @GetMapping
    @Access(AccessLevel.TEAM_ADMIN)
    List<TeamModuleView> list(TeamContext team) {
        return modules.listTeam(team.orgId(), team.teamId());
    }

    @GetMapping("/{moduleKey}/impact")
    @Access(AccessLevel.TEAM_ADMIN)
    TeamImpact impact(TeamContext team, @PathVariable String moduleKey) {
        return modules.teamImpact(team.orgId(), team.teamId(), moduleKey);
    }

    @PutMapping("/{moduleKey}/enabled")
    @Access(value = AccessLevel.TEAM_ADMIN, write = true)
    ResponseEntity<Void> enable(TeamContext team, @PathVariable String moduleKey,
            @Valid @RequestBody ModuleRequests.SetTeamEnabled body) {
        modules.setTeamEnabled(team.orgId(), team.teamId(), moduleKey, body.enabled(), body.confirmCascade(),
                team.accountId());
        return ResponseEntity.noContent().build();
    }

}
