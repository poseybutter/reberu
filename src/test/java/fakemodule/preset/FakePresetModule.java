package fakemodule.preset;

import reberu.core.module.ModuleDefinition;

public class FakePresetModule implements ModuleDefinition {

    public static final String KEY = "fake-preset";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String name() {
        return "가짜 기본 켜짐 모듈";
    }

    @Override
    public String description() {
        return "직무 유형에 모듈 목록이 없을 때 새 팀에서 켜지는 모듈";
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
    public boolean enabledByDefault() {
        return true;
    }

}
