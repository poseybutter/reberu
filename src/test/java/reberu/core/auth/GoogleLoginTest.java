package reberu.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reberu.TestcontainersConfiguration;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;
import reberu.core.auth.FakeGoogle.IdToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class GoogleLoginTest {

    private static final FakeGoogle GOOGLE = new FakeGoogle();
    private static final String FAILED = "/login?error=failed";
    private static final String START = "/start";

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Accounts accounts;

    @Autowired
    JsonMapper jsonMapper;

    private TestBrowser browser;

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
        browser = new TestBrowser(port);
    }

    @Test
    @DisplayName("인가 요청: scope openid email profile, PKCE S256, state·nonce 포함, 리디렉트 URI 고정")
    void authorizationRequestParameters() {
        Map<String, String> query = startLogin(null).query();

        assertThat(query.get("scope")).isEqualTo("openid email profile");
        assertThat(query.get("code_challenge_method")).isEqualTo("S256");
        assertThat(query).containsKeys("state", "nonce", "code_challenge");
        assertThat(query.get("redirect_uri")).isEqualTo("http://localhost:8080/login/oauth2/code/google");
        assertThat(query.get("client_id")).isEqualTo(FakeGoogle.CLIENT_ID);
    }

    @Test
    @DisplayName("기존 계정 로그인: 세션 ID 교체, 이전 쿠키 무효, 돌아갈 경로로 이동")
    void existingAccountSignsInWithNewSessionId() {
        UUID accountId = existingAccount("sub-existing", "member@example.com");
        Login login = startLogin("/teams");
        String sessionBeforeCallback = browser.cookie(TestBrowser.SESSION_COOKIE);

        HttpResponse<String> callback = callback(login, IdToken.valid("sub-existing", "Member@Example.com",
                login.nonce()).withName("새 이름"));

        assertThat(location(callback)).isEqualTo("/teams");
        String sessionAfter = browser.cookie(TestBrowser.SESSION_COOKIE);
        assertThat(sessionAfter).isNotNull().isNotEqualTo(sessionBeforeCallback);
        JsonNode session = session();
        assertThat(session.get("state").asString()).isEqualTo("AUTHENTICATED");
        assertThat(session.get("accountId").asString()).isEqualTo(accountId.toString());
        assertThat(jdbc.sql("SELECT email FROM account").query(String.class).single()).isEqualTo("member@example.com");
        assertThat(jdbc.sql("SELECT display_name FROM account").query(String.class).single()).isEqualTo("새 이름");

        browser.setCookie(TestBrowser.SESSION_COOKIE, sessionBeforeCallback);
        assertThat(session().get("state").asString()).isEqualTo("ANONYMOUS");
    }

    @Test
    @DisplayName("첫 로그인: 동의 전 계정 없음, 가입 대기로 다른 API는 401 CONSENT_REQUIRED")
    void firstLoginWaitsForConsent() {
        Login login = startLogin(null);

        HttpResponse<String> callback = callback(login, IdToken.valid("sub-new", "new@example.com", login.nonce())
                .withHostedDomain("Example.com"));

        assertThat(location(callback)).isEqualTo("/signup");
        assertThat(count("account")).isZero();
        assertThat(session().get("state").asString()).isEqualTo("SIGNUP_PENDING");
        JsonNode signup = json(browser.get("/api/auth/signup"), 200);
        assertThat(signup.get("email").asString()).isEqualTo("new@example.com");
        assertThat(signup.get("termsVersion").asString()).isEqualTo("v1");
        assertThat(json(browser.get("/api/teams"), 401).get("code").asString()).isEqualTo("CONSENT_REQUIRED");
        assertThat(json(browser.send("POST", "/api/auth/logout", null), 401).get("code").asString())
                .isEqualTo("CONSENT_REQUIRED");
    }

    @Test
    @DisplayName("동의: 계정·동의 기록 생성, 세션 ID 교체, 지금 버전이 아니면 400")
    void consentCreatesAccount() {
        signUpUntilConsent("sub-new", "new@example.com");
        String pendingSession = browser.cookie(TestBrowser.SESSION_COOKIE);

        JsonNode wrongVersion = json(browser.send("POST", "/api/auth/signup/consent",
                "{\"termsVersion\":\"v0\",\"privacyVersion\":\"v1\"}"), 400);
        assertThat(wrongVersion.get("fields").get(0).get("field").asString()).isEqualTo("termsVersion");
        assertThat(count("account")).isZero();

        JsonNode created = json(browser.send("POST", "/api/auth/signup/consent",
                "{\"termsVersion\":\"v1\",\"privacyVersion\":\"v1\"}"), 201);

        assertThat(created.get("next").asString()).isEqualTo("/start");
        assertThat(browser.cookie(TestBrowser.SESSION_COOKIE)).isNotEqualTo(pendingSession);
        assertThat(session().get("accountId").asString()).isEqualTo(created.get("accountId").asString());
        assertThat(count("account")).isEqualTo(1);
        assertThat(count("account_consent")).isEqualTo(1);
        assertThat(count("auth_pending_signup")).isZero();
        assertThat(jdbc.sql("SELECT display_name FROM account").query(String.class).single()).isEqualTo("다온 하늘");
        assertThat(jdbc.sql("SELECT state FROM account").query(String.class).single()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("동의 안 함: 계정 없음, 가입 대기·세션 삭제")
    void declineCreatesNothing() {
        signUpUntilConsent("sub-new", "new@example.com");

        HttpResponse<String> declined = browser.send("DELETE", "/api/auth/signup", null);

        assertThat(declined.statusCode()).isEqualTo(204);
        assertThat(count("account")).isZero();
        assertThat(count("auth_pending_signup")).isZero();
        assertThat(session().get("state").asString()).isEqualTo("ANONYMOUS");
    }

    @Test
    @DisplayName("T-AUTH-STATE: 다른 state·state 없음·같은 응답 재사용 거부")
    void stateMustMatchAndBeUsedOnce() {
        existingAccount("sub-existing", "member@example.com");
        Login login = startLogin(null);
        String code = GOOGLE.issue(login.challenge(), IdToken.valid("sub-existing", "member@example.com",
                login.nonce()));

        assertThat(location(browser.get("/login/oauth2/code/google?code=" + code + "&state=wrong-state")))
                .isEqualTo(FAILED);
        assertThat(location(browser.get("/login/oauth2/code/google?code=" + code))).isEqualTo(FAILED);
        assertThat(location(browser.get("/login/oauth2/code/google?code=" + code + "&state=" + login.state())))
                .isEqualTo(START);
        browser.send("POST", "/api/auth/logout", null);
        assertThat(location(browser.get("/login/oauth2/code/google?code=" + code + "&state=" + login.state())))
                .isEqualTo(FAILED);
    }

    @Test
    @DisplayName("T-AUTH-NONCE: 인가 요청과 다른 nonce 거부")
    void nonceMustMatch() {
        Login login = startLogin(null);

        assertFailed(callback(login, IdToken.valid("sub-new", "new@example.com", "other-nonce")));
    }

    @Test
    @DisplayName("T-AUTH-TOKEN: 서명·iss·aud·exp 위조, email_verified=false 거부")
    void forgedTokensAreRejected() {
        List<Function<String, IdToken>> forgeries = List.of(
                nonce -> IdToken.valid("sub-new", "new@example.com", nonce).forged(),
                nonce -> IdToken.valid("sub-new", "new@example.com", nonce).withIssuer("https://evil.example"),
                nonce -> IdToken.valid("sub-new", "new@example.com", nonce).withAudience("other-client"),
                nonce -> IdToken.valid("sub-new", "new@example.com", nonce).expired(),
                nonce -> IdToken.valid("sub-new", "new@example.com", nonce).withEmailVerified(false));

        for (Function<String, IdToken> forgery : forgeries) {
            browser = new TestBrowser(port);
            Login login = startLogin(null);
            assertFailed(callback(login, forgery.apply(login.nonce())));
        }
    }

    @Test
    @DisplayName("T-AUTH-SUB: 같은 이메일이라도 sub가 다르면 다른 계정")
    void sameEmailDifferentSubIsDifferentAccount() {
        UUID first = existingAccount("sub-first", "same@example.com");
        Login login = startLogin(null);

        HttpResponse<String> callback = callback(login, IdToken.valid("sub-second", "same@example.com", login.nonce()));

        assertThat(location(callback)).isEqualTo("/signup");
        json(browser.send("POST", "/api/auth/signup/consent", "{\"termsVersion\":\"v1\",\"privacyVersion\":\"v1\"}"),
                201);
        assertThat(count("account")).isEqualTo(2);
        assertThat(session().get("accountId").asString()).isNotEqualTo(first.toString());
    }

    @Test
    @DisplayName("T-AUTH-REDIRECT: 외부 주소·허용 목록 밖 경로는 버리고 첫 화면으로")
    void externalReturnPathsAreDropped() {
        existingAccount("sub-existing", "member@example.com");
        List<String> rejected = List.of("https://evil.example/teams", "//evil.example", "/\\evil.example",
                "/teams/../admin", "/unknown", "javascript:alert(1)");

        for (String next : rejected) {
            browser = new TestBrowser(port);
            Login login = startLogin(next);
            assertThat(location(callback(login, IdToken.valid("sub-existing", "member@example.com", login.nonce()))))
                    .as(next).isEqualTo(START);
        }

        browser = new TestBrowser(port);
        String teamPath = "/teams/0190f2a4-0000-7000-8000-000000000001/tasks";
        Login login = startLogin(teamPath + "?view=board#top");
        assertThat(location(callback(login, IdToken.valid("sub-existing", "member@example.com", login.nonce()))))
                .isEqualTo(teamPath);
    }

    @Test
    @DisplayName("정지된 계정: 로그인 거부(정지 안내), 기존 세션도 다음 요청부터 401")
    void suspendedAccountIsRejected() {
        existingAccount("sub-existing", "member@example.com");
        Login first = startLogin(null);
        callback(first, IdToken.valid("sub-existing", "member@example.com", first.nonce()));
        assertThat(session().get("state").asString()).isEqualTo("AUTHENTICATED");

        jdbc.sql("UPDATE account SET state = 'SUSPENDED'").update();

        assertThat(json(browser.get("/api/teams"), 401).get("code").asString()).isEqualTo("UNAUTHENTICATED");
        browser = new TestBrowser(port);
        Login second = startLogin(null);
        assertThat(location(callback(second, IdToken.valid("sub-existing", "member@example.com", second.nonce()))))
                .isEqualTo("/login?error=suspended");
        assertThat(session().get("state").asString()).isEqualTo("ANONYMOUS");
    }

    @Test
    @DisplayName("로그아웃: 서버 세션 삭제, 쿠키 만료")
    void logoutDeletesSession() {
        existingAccount("sub-existing", "member@example.com");
        Login login = startLogin(null);
        callback(login, IdToken.valid("sub-existing", "member@example.com", login.nonce()));
        String sessionCookie = browser.cookie(TestBrowser.SESSION_COOKIE);

        HttpResponse<String> logout = browser.send("POST", "/api/auth/logout", null);

        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.headers().allValues("Set-Cookie"))
                .anyMatch(cookie -> cookie.startsWith(TestBrowser.SESSION_COOKIE + "=;") && cookie.contains("Max-Age=0"));
        assertThat(count("auth_session")).isZero();
        browser.setCookie(TestBrowser.SESSION_COOKIE, sessionCookie);
        assertThat(session().get("state").asString()).isEqualTo("ANONYMOUS");
    }

    @Test
    @DisplayName("로그아웃은 CSRF 토큰 필요")
    void logoutRequiresCsrf() {
        existingAccount("sub-existing", "member@example.com");
        Login login = startLogin(null);
        callback(login, IdToken.valid("sub-existing", "member@example.com", login.nonce()));
        browser.removeCookie("XSRF-TOKEN");

        HttpResponse<String> withoutToken = browser.send("POST", "/api/auth/logout", null);

        assertThat(json(withoutToken, 403).get("code").asString()).isEqualTo("CSRF_INVALID");
        assertThat(session().get("state").asString()).isEqualTo("AUTHENTICATED");
        assertThat(browser.send("POST", "/api/auth/logout", null).statusCode()).isEqualTo(204);
    }

    private void signUpUntilConsent(String sub, String email) {
        Login login = startLogin(null);
        assertThat(location(callback(login, IdToken.valid(sub, email, login.nonce())))).isEqualTo("/signup");
    }

    private UUID existingAccount(String sub, String email) {
        return accounts.createWithConsent(new GoogleProfile(sub, email, "기존 이름", null, null), "v1", "v1",
                Instant.now());
    }

    private Login startLogin(String next) {
        String path = "/oauth2/authorization/google"
                + (next == null ? "" : "?next=" + URLEncoder.encode(next, StandardCharsets.UTF_8));
        HttpResponse<String> response = browser.get(path);
        assertThat(response.statusCode()).isEqualTo(302);
        URI location = URI.create(response.headers().firstValue("Location").orElseThrow());
        assertThat(location.toString()).startsWith(GOOGLE.baseUrl() + "/auth?");
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

    private void assertFailed(HttpResponse<String> callback) {
        assertThat(location(callback)).isEqualTo(FAILED);
        assertThat(count("account")).isZero();
        assertThat(count("auth_pending_signup")).isZero();
        assertThat(session().get("state").asString()).isEqualTo("ANONYMOUS");
    }

    private JsonNode session() {
        return json(browser.get("/api/auth/session"), 200);
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

}
