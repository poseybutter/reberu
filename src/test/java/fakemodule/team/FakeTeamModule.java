package fakemodule.team;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.access.TeamContext;
import reberu.core.module.ModuleDefinition;

public class FakeTeamModule implements ModuleDefinition {

    public static final String KEY = "fake";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String name() {
        return "가짜 팀 모듈";
    }

    @Override
    public String description() {
        return "테스트용 팀 범위 모듈";
    }

    @Override
    public Scope scope() {
        return Scope.TEAM;
    }

    @Override
    public Source source() {
        return Source.BUNDLED;
    }

    @RestController
    public static class Api {

        @GetMapping("/api/teams/{teamId}/mod/fake/ping")
        @Access(value = AccessLevel.TEAM_VIEWER, module = KEY)
        Map<String, String> ping(TeamContext team) {
            return Map.of("teamId", team.teamId().toString());
        }

    }

}
