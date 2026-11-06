package reberu.core.support.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reberu.TestcontainersConfiguration;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SecurityHeadersTest.ProbeController.class})
class SecurityHeadersTest {

    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data: https://lh3.googleusercontent.com; font-src 'self'; worker-src 'self'; "
            + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; "
            + "object-src 'none'; require-trusted-types-for 'script'; trusted-types reberu-html";
    private static final String OTHER_ORIGIN = "https://other.example";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    @DisplayName("200·400(방화벽 거부)·401·403·404·500 응답 모두 보안 헤더 6종")
    void everyResponseCarriesSecurityHeaders() throws Exception {
        List<MockHttpServletResponse> responses = List.of(
                perform(get("/api/security-probe/ok").with(user("member")), 204),
                perform(get("/api/security-probe/ok"), 401),
                perform(get("/api/security-probe/denied").with(user("member")), 403),
                perform(post("/api/security-probe/ok").with(user("member")), 403),
                perform(get("/api/does-not-exist").with(user("member")), 404),
                perform(get("/api/security-probe/boom").with(user("member")), 500),
                perform(get("/api/security-probe;jsessionid=x/ok"), 400));

        for (MockHttpServletResponse response : responses) {
            assertThat(response.getHeader("Content-Security-Policy")).isEqualTo(CSP);
            assertThat(response.getHeader("Strict-Transport-Security"))
                    .isEqualTo("max-age=31536000; includeSubDomains");
            assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
            assertThat(response.getHeader("Permissions-Policy"))
                    .isEqualTo("camera=(), microphone=(), geolocation=(), payment=()");
            assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
            assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        }
    }

    @Test
    @DisplayName("로그인 없는 요청: 401 UNAUTHENTICATED JSON, 로그인 화면 이동 없음")
    void unauthenticatedRequestGetsJson() throws Exception {
        MockHttpServletResponse response = perform(get("/api/security-probe/ok"), 401);

        assertThat(code(response)).isEqualTo("UNAUTHENTICATED");
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull();
        assertThat(perform(get("/login"), 401).getContentAsString()).doesNotContain("<form");
    }

    @Test
    @DisplayName("권한 거부: 403 FORBIDDEN_ACTION JSON")
    void accessDeniedGetsJson() throws Exception {
        MockHttpServletResponse response = perform(get("/api/security-probe/denied").with(user("member")), 403);

        assertThat(code(response)).isEqualTo("FORBIDDEN_ACTION");
    }

    @Test
    @DisplayName("다른 출처 preflight: 403, 허용 헤더 없음")
    void crossOriginPreflightIsRejected() throws Exception {
        MockHttpServletResponse response = perform(options("/api/security-probe/ok")
                .header(HttpHeaders.ORIGIN, OTHER_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"), 403);

        assertThat(code(response)).isEqualTo("FORBIDDEN_ACTION");
        assertNoCorsHeaders(response);
    }

    @Test
    @DisplayName("다른 출처 요청: 로그인 상태여도 403, 허용 헤더 없음")
    void crossOriginRequestIsRejected() throws Exception {
        MockHttpServletResponse response = perform(get("/api/security-probe/ok")
                .with(user("member")).header(HttpHeaders.ORIGIN, OTHER_ORIGIN), 403);

        assertNoCorsHeaders(response);
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo(CSP);
    }

    @Test
    @DisplayName("같은 출처 Origin 헤더 요청은 통과")
    void sameOriginRequestPasses() throws Exception {
        MockHttpServletResponse response = perform(get("/api/security-probe/ok")
                .with(user("member")).header(HttpHeaders.ORIGIN, "http://localhost"), 204);

        assertNoCorsHeaders(response);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, int status) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        return response;
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return jsonMapper.readTree(response.getContentAsString()).get("code").asString();
    }

    private static void assertNoCorsHeaders(MockHttpServletResponse response) {
        assertThat(response.getHeaderNames()).noneMatch(name -> name.startsWith("Access-Control-"));
    }

    @RestController
    @RequestMapping("/api/security-probe")
    static class ProbeController {

        @GetMapping("/ok")
        ResponseEntity<Void> ok() {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/denied")
        void denied() {
            throw new AccessDeniedException("denied");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("boom");
        }

    }

}
