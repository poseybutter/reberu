package reberu.core.tenancy.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.access.MembershipDirectory.Role;
import reberu.core.access.TeamContext;
import reberu.core.tenancy.dto.TeamSettingsRequests;
import reberu.core.tenancy.service.TeamFieldService;
import reberu.core.tenancy.service.TeamFieldService.FieldView;
import reberu.core.tenancy.service.TeamFieldService.LabelView;
import reberu.core.tenancy.service.TeamSettingsService;
import reberu.core.tenancy.service.TeamSettingsService.TeamOverview;
import reberu.core.tenancy.service.TeamSettingsService.TeamSettings;

@RestController
@RequestMapping("/api/teams/{teamId}")
class TeamSettingsController {

    private final TeamSettingsService settings;
    private final TeamFieldService fields;

    TeamSettingsController(TeamSettingsService settings, TeamFieldService fields) {
        this.settings = settings;
        this.fields = fields;
    }

    @GetMapping("/overview")
    @Access(AccessLevel.TEAM_ADMIN)
    TeamOverview overview(TeamContext team) {
        return settings.overview(team.teamId());
    }

    @GetMapping("/settings")
    @Access(AccessLevel.TEAM_ADMIN)
    TeamSettings settings(TeamContext team) {
        return settings.settings(team.orgId(), team.teamId());
    }

    @PatchMapping("/settings")
    @Access(value = AccessLevel.TEAM_ADMIN, write = true)
    TeamSettings update(TeamContext team, @Valid @RequestBody TeamSettingsRequests.UpdateSettings body) {
        return settings.update(team.orgId(), team.teamId(), team.accountId(), body.toChange());
    }

    @GetMapping("/labels")
    @Access(AccessLevel.TEAM_VIEWER)
    List<LabelView> labels(TeamContext team, @RequestParam(defaultValue = "false") boolean includeHidden) {
        return fields.labels(team.teamId(), includeHidden && admin(team));
    }

    @PostMapping("/labels")
    @Access(value = AccessLevel.TEAM_ADMIN, write = true)
    ResponseEntity<TeamSettingsRequests.LabelCreated> createLabel(TeamContext team,
            @Valid @RequestBody TeamSettingsRequests.CreateLabel body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(new TeamSettingsRequests.LabelCreated(
                fields.createLabel(team.orgId(), team.teamId(), team.accountId(), body.name())));
    }

    @PatchMapping("/labels/{labelId}")
    @Access(value = AccessLevel.TEAM_ADMIN, write = true)
    LabelView updateLabel(TeamContext team, @PathVariable UUID labelId,
            @Valid @RequestBody TeamSettingsRequests.UpdateLabel body) {
        return fields.updateLabel(team.teamId(), labelId, team.accountId(), body.toChange());
    }

    @GetMapping("/custom-fields")
    @Access(AccessLevel.TEAM_VIEWER)
    List<FieldView> customFields(TeamContext team, @RequestParam(defaultValue = "false") boolean includeHidden) {
        return fields.fields(team.teamId(), includeHidden && admin(team));
    }

    @PostMapping("/custom-fields")
    @Access(value = AccessLevel.TEAM_ADMIN, write = true)
    ResponseEntity<TeamSettingsRequests.FieldCreated> createField(TeamContext team,
            @Valid @RequestBody TeamSettingsRequests.CreateField body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(new TeamSettingsRequests.FieldCreated(
                fields.createField(team.orgId(), team.teamId(), team.accountId(), body.toNewField())));
    }

    @PatchMapping("/custom-fields/{fieldId}")
    @Access(value = AccessLevel.TEAM_ADMIN, write = true)
    FieldView updateField(TeamContext team, @PathVariable UUID fieldId,
            @Valid @RequestBody TeamSettingsRequests.UpdateField body) {
        return fields.updateField(team.teamId(), fieldId, team.accountId(), body.toChange());
    }

    private static boolean admin(TeamContext team) {
        return team.role() == Role.TEAM_ADMIN || team.orgAdmin();
    }

}
