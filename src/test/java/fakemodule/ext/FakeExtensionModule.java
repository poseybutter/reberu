package fakemodule.ext;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.access.TeamContext;
import reberu.core.module.ModuleDefinition;

public class FakeExtensionModule implements ModuleDefinition {

    public static final String KEY = "fake-ext";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String name() {
        return "가짜 확장 모듈";
    }

    @Override
    public String description() {
        return "테스트용 확장 팀 모듈";
    }

    @Override
    public Scope scope() {
        return Scope.TEAM;
    }

    @Override
    public Source source() {
        return Source.EXTENSION;
    }

    @RestController
    public static class Api {

        @GetMapping("/api/teams/{teamId}/mod/fake-ext/ping")
        @Access(value = AccessLevel.TEAM_VIEWER, module = KEY)
        Map<String, String> ping(TeamContext team) {
            return Map.of("teamId", team.teamId().toString());
        }

    }

}
