package fakemodule.org;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.access.OrgContext;
import reberu.core.module.ModuleDefinition;

public class FakeOrgModule implements ModuleDefinition {

    public static final String KEY = "fake-org";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String name() {
        return "가짜 조직 모듈";
    }

    @Override
    public String description() {
        return "테스트용 조직 범위 모듈";
    }

    @Override
    public Scope scope() {
        return Scope.ORG;
    }

    @Override
    public Source source() {
        return Source.BUNDLED;
    }

    @RestController
    public static class Api {

        @GetMapping("/api/orgs/{orgId}/mod/fake-org/ping")
        @Access(value = AccessLevel.ORG_MEMBER, module = KEY)
        Map<String, String> ping(OrgContext org) {
            return Map.of("orgId", org.orgId().toString());
        }

    }

}
