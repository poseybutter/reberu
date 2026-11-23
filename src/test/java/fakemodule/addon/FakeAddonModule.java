package fakemodule.addon;

import fakemodule.team.FakeTeamModule;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.access.TeamContext;
import reberu.core.module.ModuleDefinition;

public class FakeAddonModule implements ModuleDefinition {

    public static final String KEY = "fake-addon";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String name() {
        return "가짜 덧붙임 모듈";
    }

    @Override
    public String description() {
        return "가짜 팀 모듈이 있어야 켜지는 모듈";
    }

    @Override
    public Scope scope() {
        return Scope.TEAM;
    }

    @Override
    public Source source() {
        return Source.BUNDLED;
    }

    @Override
    public Set<String> requires() {
        return Set.of(FakeTeamModule.KEY);
    }

    @RestController
    public static class Api {

        @GetMapping("/api/teams/{teamId}/mod/fake-addon/ping")
        @Access(value = AccessLevel.TEAM_VIEWER, module = KEY)
        Map<String, String> ping(TeamContext team) {
            return Map.of("teamId", team.teamId().toString());
        }

    }

}
