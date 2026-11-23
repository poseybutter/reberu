package reberu.core.access.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import reberu.TestcontainersConfiguration;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.access.AccessLevel.Scope;
import reberu.core.access.ModuleSwitch;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccessDeclarationTest {

    private static final ModuleSwitch MODULES = new ModuleSwitch() {

        @Override
        public Optional<Scope> installedScope(String moduleKey) {
            return switch (moduleKey) {
                case "fake" -> Optional.of(Scope.TEAM);
                case "fake-org" -> Optional.of(Scope.ORG);
                default -> Optional.empty();
            };
        }

        @Override
        public Optional<String> owner(Class<?> type) {
            return type == ModuleProbe.class ? Optional.of("fake") : Optional.empty();
        }

        @Override
        public boolean enabledInOrg(UUID orgId, String moduleKey) {
            return false;
        }

        @Override
        public boolean enabledInTeam(UUID teamId, String moduleKey) {
            return false;
        }

    };

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    ModuleSwitch modules;

    @Test
    @DisplayName("T-AUTHZ-DECLARED·ARCH-TEST-02: 모든 /api 엔드포인트에 권한 선언, 조직·팀 경로와 권한 범위 일치")
    void everyEndpointDeclaresAccess() {
        assertThat(handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream())
                .filter(AccessDeclarations::isApi)).isNotEmpty();
        assertThat(AccessDeclarations.violations(handlerMapping.getHandlerMethods(), modules)).isEmpty();
    }

    @Test
    @DisplayName("ARCH-TEST-02 위반 사례: 선언 없음, 범위와 경로 불일치, 팀 경로에 orgId, 같은 경로 조각 반복, 관리자·쓰기 API 손님 허용")
    void detectsViolations() throws Exception {
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        handlers.put(info("/api/undeclared"), handler("undeclared"));
        handlers.put(info("/api/orgs/{orgId}/thing"), handler("account"));
        handlers.put(info("/api/thing"), handler("orgAdmin"));
        handlers.put(info("/api/orgs/{orgId}/teams/{teamId}/thing"), handler("teamMember"));
        handlers.put(info("/api/teams/{teamId}/thing"), handler("teamMember"));
        handlers.put(info("/api/teams/{teamId}/x/{id}/x"), handler("teamMember"));
        handlers.put(info("/api/x/{id}/x"), handler("teamMember"));
        handlers.put(info("/api/orgs/{orgId}/admin-guest"), handler("orgAdminGuest"));
        handlers.put(info("/api/orgs/{orgId}/member-guest"), handler("orgMemberGuest"));
        handlers.put(info("/api/orgs/{orgId}/write-guest"), handler("orgMemberGuestWrite"));
        handlers.put(info("/not-api/thing"), handler("undeclared"));

        assertThat(AccessDeclarations.violations(handlers, MODULES))
                .hasSize(7)
                .anySatisfy(v -> assertThat(v).startsWith("손님 허용은 읽기 API에만: /api/orgs/{orgId}/write-guest"))
                .anySatisfy(v -> assertThat(v).startsWith("손님 허용은 조직 멤버 권한에만: /api/orgs/{orgId}/admin-guest"))
                .anySatisfy(v -> assertThat(v).startsWith("팀 권한인데 {teamId} 경로 없음: /api/x/{id}/x"))
                .anySatisfy(v -> assertThat(v).startsWith("권한 선언 없음: /api/undeclared"))
                .anySatisfy(v -> assertThat(v).startsWith("조직·팀 경로인데 조직·팀 권한 아님: /api/orgs/{orgId}/thing"))
                .anySatisfy(v -> assertThat(v).startsWith("조직 권한인데 {orgId} 경로 없음: /api/thing"))
                .anySatisfy(v -> assertThat(v).startsWith("팀 권한 경로에 {orgId} 함께 있음"));
    }

    @Test
    @DisplayName("API-MOD-01·02·V3 위반 사례: 경로와 선언 key 다름, /mod 밖 모듈 선언, 설치 안 된 key, 범위 다름, 모듈 API에 key 없음")
    void detectsModuleViolations() throws Exception {
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        handlers.put(info("/api/teams/{teamId}/mod/fake/ok"), moduleHandler("fake"));
        handlers.put(info("/api/orgs/{orgId}/mod/fake-org/ok"), handler("fakeOrg"));
        handlers.put(info("/api/teams/{teamId}/mod/other/thing"), moduleHandler("fake"));
        handlers.put(info("/api/teams/{teamId}/mod/fake/undeclared"), handler("teamMember"));
        handlers.put(info("/api/teams/{teamId}/plain"), moduleHandler("fake"));
        handlers.put(info("/api/teams/{teamId}/x/mod/fake"), moduleHandler("fake"));
        handlers.put(info("/api/teams/{teamId}/mod/gone/thing"), handler("gone"));
        handlers.put(info("/api/teams/{teamId}/mod/fake-org/thing"), handler("fakeOrgOnTeam"));
        handlers.put(info("/api/teams/{teamId}/mod/missing/thing"), moduleHandler("missing"));

        assertThat(AccessDeclarations.violations(handlers, MODULES))
                .hasSize(8)
                .anySatisfy(v -> assertThat(v).startsWith(
                        "경로의 모듈 key와 선언한 모듈 key 다름(API-MOD-02): /api/teams/{teamId}/mod/other/thing"))
                .anySatisfy(v -> assertThat(v).startsWith(
                        "경로의 모듈 key와 선언한 모듈 key 다름(API-MOD-02): /api/teams/{teamId}/mod/fake/undeclared"))
                .anySatisfy(v -> assertThat(v).startsWith(
                        "모듈 선언인데 /mod/{key} 경로 아님(API-MOD-01): /api/teams/{teamId}/plain"))
                .anySatisfy(v -> assertThat(v).startsWith("모듈 경로는 /api/orgs/{orgId}/mod/{key} 또는 "
                        + "/api/teams/{teamId}/mod/{key}(API-MOD-01): /api/teams/{teamId}/x/mod/fake"))
                .anySatisfy(v -> assertThat(v).startsWith("설치되지 않은 모듈 key 선언: /api/teams/{teamId}/mod/gone/thing"))
                .anySatisfy(v -> assertThat(v).startsWith("모듈 범위와 권한 범위 다름: /api/teams/{teamId}/mod/fake-org/thing"))
                .anySatisfy(v -> assertThat(v).startsWith(
                        "모듈 API에 그 모듈 key 선언 없음(V3): /api/teams/{teamId}/mod/missing/thing"));
    }

    @Test
    @DisplayName("SEC-AUTHZ-02: 선언 없는 /api 엔드포인트가 있으면 시작 단계 검사가 실패")
    void startupCheckFailsOnUndeclaredEndpoint() throws Exception {
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.registerMapping(info("/api/undeclared"), new Probe(), Probe.class.getDeclaredMethod("undeclared"));
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("requestMappingHandlerMapping", mapping));

        StaticListableBeanFactory switches = new StaticListableBeanFactory(Map.of("moduleSwitch", MODULES));

        assertThatThrownBy(() -> AccessConfiguration.accessDeclarationCheck(
                beans.getBeanProvider(RequestMappingHandlerMapping.class),
                switches.getBeanProvider(ModuleSwitch.class)).onApplicationEvent(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SEC-AUTHZ-02")
                .hasMessageContaining("/api/undeclared");
    }

    private static RequestMappingInfo info(String path) {
        return RequestMappingInfo.paths(path).methods(RequestMethod.GET).build();
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        Method method = Probe.class.getDeclaredMethod(name);
        return new HandlerMethod(new Probe(), method);
    }

    private static HandlerMethod moduleHandler(String name) throws NoSuchMethodException {
        Method method = ModuleProbe.class.getDeclaredMethod(name);
        return new HandlerMethod(new ModuleProbe(), method);
    }

    static class ModuleProbe {

        @Access(value = AccessLevel.TEAM_MEMBER, module = "fake")
        void fake() {
        }

        @Access(value = AccessLevel.TEAM_MEMBER, module = "missing")
        void missing() {
        }

    }

    static class Probe {

        @Access(value = AccessLevel.ORG_MEMBER, module = "fake-org")
        void fakeOrg() {
        }

        @Access(value = AccessLevel.TEAM_MEMBER, module = "fake-org")
        void fakeOrgOnTeam() {
        }

        @Access(value = AccessLevel.TEAM_MEMBER, module = "gone")
        void gone() {
        }

        void undeclared() {
        }

        @Access(AccessLevel.ACCOUNT)
        void account() {
        }

        @Access(AccessLevel.ORG_ADMIN)
        void orgAdmin() {
        }

        @Access(AccessLevel.TEAM_MEMBER)
        void teamMember() {
        }

        @Access(value = AccessLevel.ORG_ADMIN, guestAllowed = true)
        void orgAdminGuest() {
        }

        @Access(value = AccessLevel.ORG_MEMBER, guestAllowed = true)
        void orgMemberGuest() {
        }

        @Access(value = AccessLevel.ORG_MEMBER, write = true, guestAllowed = true)
        void orgMemberGuestWrite() {
        }

    }

}
