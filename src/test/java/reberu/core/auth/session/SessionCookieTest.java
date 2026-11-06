package reberu.core.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reberu.TestcontainersConfiguration;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, SessionCookieTest.ProbeConfiguration.class})
class SessionCookieTest {

    private static final String ACCOUNT = "0190f2a4-0000-7000-8000-000000000001";

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM auth_session").update();
    }

    @Test
    @DisplayName("세션 쿠키: __Host- 이름, HttpOnly·Secure·SameSite=Lax·Path=/, Domain·만료 시각 없음")
    void sessionCookieAttributes() throws Exception {
        String setCookie = sessionCookieHeader(get("/session-probe/login?account=" + ACCOUNT, null));

        assertThat(setCookie).startsWith(SessionConfiguration.COOKIE_NAME + "=");
        List<String> attributes = List.of(setCookie.split(";\\s*")).subList(1, setCookie.split(";\\s*").length);
        assertThat(attributes).containsExactlyInAnyOrder("Path=/", "Secure", "HttpOnly", "SameSite=Lax");
    }

    @Test
    @DisplayName("쿠키의 세션 ID로 로그인 상태 유지")
    void cookieRestoresSession() throws Exception {
        String cookie = cookieValue(get("/session-probe/login?account=" + ACCOUNT, null));

        assertThat(get("/session-probe/whoami", cookie).body()).isEqualTo(ACCOUNT);
    }

    @Test
    @DisplayName("DB에 저장된 값으로 만든 세션 쿠키는 인정되지 않음")
    void storedHashCannotBeUsedAsCookie() throws Exception {
        String cookie = cookieValue(get("/session-probe/login?account=" + ACCOUNT, null));
        String storedHash = jdbc.sql("SELECT session_id_hash FROM auth_session").query(String.class).single();

        HttpResponse<String> forged = get("/session-probe/whoami", storedHash);

        assertThat(storedHash).isNotEqualTo(cookie);
        assertThat(forged.body()).isEqualTo("none");
    }

    @Test
    @DisplayName("세션 ID 재발급: 새 쿠키 발급, 이전 쿠키 무효")
    void changedSessionIdInvalidatesOldCookie() throws Exception {
        String oldCookie = cookieValue(get("/session-probe/login?account=" + ACCOUNT, null));

        String newCookie = cookieValue(get("/session-probe/rotate", oldCookie));

        assertThat(newCookie).isNotEqualTo(oldCookie);
        assertThat(get("/session-probe/whoami", newCookie).body()).isEqualTo(ACCOUNT);
        assertThat(get("/session-probe/whoami", oldCookie).body()).isEqualTo("none");
    }

    private HttpResponse<String> get(String path, String sessionId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (sessionId != null) {
            request.header(HttpHeaders.COOKIE, SessionConfiguration.COOKIE_NAME + "=" + sessionId);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response;
    }

    private static String sessionCookieHeader(HttpResponse<String> response) {
        return response.headers().allValues(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(SessionConfiguration.COOKIE_NAME + "="))
                .findFirst()
                .orElseThrow();
    }

    private static String cookieValue(HttpResponse<String> response) {
        String header = sessionCookieHeader(response);
        return header.substring(SessionConfiguration.COOKIE_NAME.length() + 1, header.indexOf(';'));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        SecurityFilterChain sessionProbeChain(HttpSecurity http) throws Exception {
            http.securityMatcher("/session-probe/**")
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
            return http.build();
        }

        @Bean
        ProbeController sessionProbeController() {
            return new ProbeController();
        }

    }

    @RestController
    @RequestMapping("/session-probe")
    static class ProbeController {

        @GetMapping("/login")
        String login(@RequestParam UUID account, HttpServletRequest request) {
            request.getSession(true).setAttribute(AuthSession.AUTHENTICATION,
                    new SessionAuthentication(account, Instant.now()));
            return "ok";
        }

        @GetMapping("/rotate")
        String rotate(HttpServletRequest request) {
            request.changeSessionId();
            return "ok";
        }

        @GetMapping("/whoami")
        String whoami(HttpServletRequest request) {
            HttpSession session = request.getSession(false);
            Object authentication = session == null ? null : session.getAttribute(AuthSession.AUTHENTICATION);
            return authentication instanceof SessionAuthentication value ? value.accountId().toString() : "none";
        }

    }

}
