package reberu.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reberu.TestcontainersConfiguration;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;
import reberu.core.auth.FakeGoogle.IdToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, ReauthTest.ProbeController.class})
class ReauthTest {

    private static final FakeGoogle GOOGLE = new FakeGoogle();
    private static final String SUB = "sub-admin";
    private static final String EMAIL = "admin@example.com";
    private static final String LOCKED = "/api/reauth-probe";
    private static final String FAILED = "/login?error=failed";

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Accounts accounts;

    @Autowired
    JsonMapper jsonMapper;

    private TestBrowser browser;
    private UUID accountId;

    @DynamicPropertySource
    static void googleEndpoints(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.google.authorization-uri",
                () -> GOOGLE.baseUrl() + "/auth");
        registry.add("spring.security.oauth2.client.provider.google.token-uri", () -> GOOGLE.baseUrl() + "/token");
        registry.add("spring.security.oauth2.client.provider.google.jwk-set-uri", () -> GOOGLE.baseUrl() + "/jwks");
    }

    @AfterAll
    static void stopGoogle() {
        GOOGLE.stop();
    }

    @BeforeEach
    void setUp() {
        for (String table : List.of("account_consent", "account", "auth_pending_signup", "auth_session")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        accountId = accounts.createWithConsent(new GoogleProfile(SUB, EMAIL, "관리자", null, null), "v1", "v1",
                Instant.now());
        browser = new TestBrowser(port);
    }

    @Test
    @DisplayName("T-SESS-REAUTH: 인증 1시간이 지나면 최근 인증 API는 401 REAUTH_REQUIRED, 그 밖의 API는 그대로")
    void staleAuthenticationRequiresReauth() {
        Instant twoHoursAgo = Instant.now().minus(Duration.ofHours(2));
        signIn(twoHoursAgo);

        assertThat(json(browser.get(LOCKED), 401).get("code").asString()).isEqualTo("REAUTH_REQUIRED");
        JsonNode session = json(browser.get("/api/auth/session"), 200);
        assertThat(session.get("state").asString()).isEqualTo("AUTHENTICATED");
        assertThat(Instant.parse(session.get("authenticatedAt").asString()).getEpochSecond())
                .isEqualTo(twoHoursAgo.getEpochSecond());
    }

    @Test
    @DisplayName("최근 인증이면 최근 인증 API 통과")
    void recentAuthenticationPasses() {
        signIn(Instant.now());

        assertThat(browser.get(LOCKED).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("재인증: prompt=login·max_age=0, 세션 ID 교체, 인증 시각 갱신 뒤 최근 인증 API 통과")
    void reauthRefreshesAuthentication() {
        signIn(Instant.now().minus(Duration.ofHours(2)));
        String sessionBefore = browser.cookie(TestBrowser.SESSION_COOKIE);

        Login reauth = startLogin("/oauth2/authorization/google?reauth=true&next=/me");
        HttpResponse<String> callback = callback(reauth, token(reauth).withAuthTime(Instant.now()));

        assertThat(reauth.query()).containsEntry("prompt", "login").containsEntry("max_age", "0");
        assertThat(location(callback)).isEqualTo("/me");
        assertThat(browser.cookie(TestBrowser.SESSION_COOKIE)).isNotEqualTo(sessionBefore);
        assertThat(browser.get(LOCKED).statusCode()).isEqualTo(204);
        browser.setCookie(TestBrowser.SESSION_COOKIE, sessionBefore);
        assertThat(json(browser.get("/api/auth/session"), 200).get("state").asString()).isEqualTo("ANONYMOUS");
    }

    @Test
    @DisplayName("로그인 상태에서 시작한 로그인은 reauth 없이도 재인증 요청")
    void loginFromSignedInSessionIsReauth() {
        signIn(Instant.now());

        Login login = startLogin("/oauth2/authorization/google");

        assertThat(login.query()).containsEntry("prompt", "login").containsEntry("max_age", "0");
    }

    @Test
    @DisplayName("재인증 sub 불일치: 세션 삭제, 로그인 실패")
    void reauthWithOtherAccountDeletesSession() {
        signIn(Instant.now());
        Login reauth = startLogin("/oauth2/authorization/google?reauth=true");

        HttpResponse<String> callback = callback(reauth,
                IdToken.valid("sub-other", "other@example.com", reauth.nonce()).withAuthTime(Instant.now()));

        assertThat(location(callback)).isEqualTo(FAILED);
        assertThat(json(browser.get("/api/auth/session"), 200).get("state").asString()).isEqualTo("ANONYMOUS");
        assertThat(count("auth_session")).isZero();
        assertThat(count("auth_pending_signup")).isZero();
    }

    @Test
    @DisplayName("재인증 auth_time 없음·10분보다 오래됨: 세션 삭제, 로그인 실패")
    void reauthWithoutFreshAuthTimeFails() {
        for (Instant authTime : Arrays.asList(null, Instant.now().minus(Duration.ofMinutes(20)))) {
            browser = new TestBrowser(port);
            signIn(Instant.now());
            Login reauth = startLogin("/oauth2/authorization/google?reauth=true");

            HttpResponse<String> callback = callback(reauth, token(reauth).withAuthTime(authTime));

            assertThat(location(callback)).as(String.valueOf(authTime)).isEqualTo(FAILED);
            assertThat(json(browser.get("/api/auth/session"), 200).get("state").asString()).isEqualTo("ANONYMOUS");
        }
    }

    @Test
    @DisplayName("미래의 auth_time은 지금 시각으로 자름")
    void futureAuthTimeIsClamped() {
        Instant before = Instant.now();
        signIn(Instant.now().plus(Duration.ofDays(1)));

        Instant authenticatedAt = Instant.parse(json(browser.get("/api/auth/session"), 200)
                .get("authenticatedAt").asString());

        assertThat(authenticatedAt).isBetween(before.minusSeconds(1), Instant.now());
    }

    private void signIn(Instant authTime) {
        Login login = startLogin("/oauth2/authorization/google");
        assertThat(location(callback(login, token(login).withAuthTime(authTime)))).isEqualTo("/start");
    }

    private static IdToken token(Login login) {
        return IdToken.valid(SUB, EMAIL, login.nonce());
    }

    private Login startLogin(String path) {
        HttpResponse<String> response = browser.get(path);
        assertThat(response.statusCode()).isEqualTo(302);
        URI location = URI.create(response.headers().firstValue("Location").orElseThrow());
        Map<String, String> query = new HashMap<>();
        for (String pair : location.getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            query.put(pair.substring(0, separator),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return new Login(query.get("state"), query.get("nonce"), query.get("code_challenge"), query);
    }

    private HttpResponse<String> callback(Login login, IdToken idToken) {
        String code = GOOGLE.issue(login.challenge(), idToken);
        return browser.get("/login/oauth2/code/google?code=" + code + "&state="
                + URLEncoder.encode(login.state(), StandardCharsets.UTF_8));
    }

    private JsonNode json(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return jsonMapper.readTree(response.body());
    }

    private static String location(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(302);
        return response.headers().firstValue("Location").orElseThrow();
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private record Login(String state, String nonce, String challenge, Map<String, String> query) {
    }

    @RestController
    static class ProbeController {

        @GetMapping(LOCKED)
        @RequiresRecentAuth
        ResponseEntity<Void> locked() {
            return ResponseEntity.noContent().build();
        }

    }

}
