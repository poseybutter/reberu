package reberu.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
class WithdrawalLoginTest {

    private static final FakeGoogle GOOGLE = new FakeGoogle();
    private static final String SUB = "sub-withdrawn";
    private static final String EMAIL = "leaving@example.com";

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
    @DisplayName("SEC-PRIV-04·SEC-SESS: 탈퇴하면 지금 세션은 바로 끊기고 다른 세션도 지워지며, 같은 Google 계정은 새 가입이 됨")
    void withdrawalEndsSessionsAndForgetsSub() {
        UUID accountId = accounts.createWithConsent(new GoogleProfile(SUB, EMAIL, "떠나는 사람", null, null), "v1",
                "v1", Instant.now());
        assertThat(signIn().statusCode()).isEqualTo(302);
        insertOtherSession(accountId);
        assertThat(session().get("state").asString()).isEqualTo("AUTHENTICATED");

        HttpResponse<String> withdrawn = browser.send("POST", "/api/me/withdraw",
                "{\"confirmText\":\"탈퇴합니다\",\"orgNameConfirmations\":[]}");

        assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(204);
        assertThat(browser.cookie(TestBrowser.SESSION_COOKIE)).isNull();
        assertThat(session().get("state").asString()).isEqualTo("ANONYMOUS");
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql(
                        "SELECT COUNT(*) FROM auth_session WHERE account_id = :account")
                .param("account", accountId.toString()).query(Integer.class).single() == 0);
        assertThat(accounts.findByGoogleSub(SUB)).isEmpty();

        assertThat(location(signIn())).isEqualTo("/signup");
        assertThat(session().get("state").asString()).isEqualTo("SIGNUP_PENDING");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM account").query(Integer.class).single()).isOne();
    }

    private HttpResponse<String> signIn() {
        HttpResponse<String> response = browser.get("/oauth2/authorization/google");
        assertThat(response.statusCode()).isEqualTo(302);
        URI location = URI.create(response.headers().firstValue("Location").orElseThrow());
        Map<String, String> query = new HashMap<>();
        for (String pair : location.getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            query.put(pair.substring(0, separator),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        String code = GOOGLE.issue(query.get("code_challenge"), IdToken.valid(SUB, EMAIL, query.get("nonce")));
        return browser.get("/login/oauth2/code/google?code=" + code + "&state="
                + URLEncoder.encode(query.get("state"), StandardCharsets.UTF_8));
    }

    private void insertOtherSession(UUID accountId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO auth_session (session_id_hash, account_id, authenticated_at, created_at,
                            last_used_at, expires_at)
                        VALUES (:hash, :account, :now, :now, :now, :expires)
                        """)
                .param("hash", (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""))
                .param("account", accountId.toString())
                .param("now", now)
                .param("expires", now.plusHours(1))
                .update();
    }

    private JsonNode session() {
        HttpResponse<String> response = browser.get("/api/auth/session");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return jsonMapper.readTree(response.body());
    }

    private static String location(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(302);
        return response.headers().firstValue("Location").orElseThrow();
    }

}
