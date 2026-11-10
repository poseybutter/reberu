package reberu.core.access.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
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

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccessDeclarationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("T-AUTHZ-DECLARED·ARCH-TEST-02: 모든 /api 엔드포인트에 권한 선언, 조직·팀 경로와 권한 범위 일치")
    void everyEndpointDeclaresAccess() {
        assertThat(handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream())
                .filter(AccessDeclarations::isApi)).isNotEmpty();
        assertThat(AccessDeclarations.violations(handlerMapping.getHandlerMethods())).isEmpty();
    }

    @Test
    @DisplayName("ARCH-TEST-02 위반 사례: 선언 없음, 범위와 경로 불일치, 팀 경로에 orgId")
    void detectsViolations() throws Exception {
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        handlers.put(info("/api/undeclared"), handler("undeclared"));
        handlers.put(info("/api/orgs/{orgId}/thing"), handler("account"));
        handlers.put(info("/api/thing"), handler("orgAdmin"));
        handlers.put(info("/api/orgs/{orgId}/teams/{teamId}/thing"), handler("teamMember"));
        handlers.put(info("/api/teams/{teamId}/thing"), handler("teamMember"));
        handlers.put(info("/not-api/thing"), handler("undeclared"));

        assertThat(AccessDeclarations.violations(handlers))
                .hasSize(4)
                .anySatisfy(v -> assertThat(v).startsWith("권한 선언 없음: /api/undeclared"))
                .anySatisfy(v -> assertThat(v).startsWith("조직·팀 경로인데 조직·팀 권한 아님: /api/orgs/{orgId}/thing"))
                .anySatisfy(v -> assertThat(v).startsWith("조직 권한인데 {orgId} 경로 없음: /api/thing"))
                .anySatisfy(v -> assertThat(v).startsWith("팀 권한 경로에 {orgId} 함께 있음"));
    }

    @Test
    @DisplayName("SEC-AUTHZ-02: 선언 없는 /api 엔드포인트가 있으면 시작 단계 검사가 실패")
    void startupCheckFailsOnUndeclaredEndpoint() throws Exception {
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.registerMapping(info("/api/undeclared"), new Probe(), Probe.class.getDeclaredMethod("undeclared"));
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("requestMappingHandlerMapping", mapping));

        assertThatThrownBy(() -> AccessConfiguration.accessDeclarationCheck(
                beans.getBeanProvider(RequestMappingHandlerMapping.class)).onApplicationEvent(null))
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

    static class Probe {

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

    }

}
