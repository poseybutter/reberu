package fakemodule.orgaddon;

import fakemodule.org.FakeOrgModule;
import java.util.Set;
import reberu.core.module.ModuleDefinition;

public class FakeOrgAddonModule implements ModuleDefinition {

    public static final String KEY = "fake-org-addon";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String name() {
        return "가짜 조직 덧붙임 모듈";
    }

    @Override
    public String description() {
        return "가짜 조직 모듈이 있어야 켜지는 모듈";
    }

    @Override
    public Scope scope() {
        return Scope.ORG;
    }

    @Override
    public Source source() {
        return Source.BUNDLED;
    }

    @Override
    public Set<String> requires() {
        return Set.of(FakeOrgModule.KEY);
    }

}
