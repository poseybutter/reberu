package reberu.core.support.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static reberu.TestCsrf.csrfToken;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import reberu.TestcontainersConfiguration;
import reberu.core.auth.security.AccountAuthentication;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "reberu.rate-limit.rules.login.limit=2",
        "reberu.rate-limit.rules.org-create.limit=2",
        "reberu.rate-limit.rules.default.limit=3"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class RateLimitTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    @DisplayName("T-RATE·SEC-RATE-01: 로그인 시작·콜백은 IP마다 한도, 넘으면 429와 Retry-After, 보안 로그는 IP 가림")
    void limitsLoginByIp(CapturedOutput output) throws Exception {
        assertThat(perform(fromIp(get("/oauth2/authorization/google"), "198.51.100.7")).getStatus()).isEqualTo(302);
        assertThat(perform(fromIp(get("/login/oauth2/code/google"), "198.51.100.7")).getStatus()).isNotEqualTo(429);

        MockHttpServletResponse limited = perform(fromIp(get("/oauth2/authorization/google"), "198.51.100.7"));

        assertLimited(limited);
        assertThat(perform(fromIp(get("/oauth2/authorization/google"), "198.51.100.8")).getStatus()).isEqualTo(302);
        assertThat(output).contains("요청 제한 초과: rule=SEC-RATE-01, key=ip:198.51.100.*")
                .doesNotContain("198.51.100.7");
    }

    @Test
    @DisplayName("T-RATE: 기본 설정은 X-Forwarded-For를 믿지 않음")
    void ignoresForwardedForByDefault() throws Exception {
        for (int i = 0; i < 2; i++) {
            perform(fromIp(get("/oauth2/authorization/google"), "198.51.100.20")
                    .header("X-Forwarded-For", "203.0.113." + i));
        }

        assertLimited(perform(fromIp(get("/oauth2/authorization/google"), "198.51.100.20")
                .header("X-Forwarded-For", "203.0.113.99")));
    }

    @Test
    @DisplayName("T-RATE·SEC-RATE-05: 조직 만들기는 계정마다 한도, 실패한 요청도 셈")
    void limitsOrgCreationPerAccount() throws Exception {
        UUID account = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            assertThat(perform(asAccount(createOrg(), account)).getStatus()).isEqualTo(400);
        }

        assertLimited(perform(asAccount(createOrg(), account)));
        assertThat(perform(asAccount(createOrg(), UUID.randomUUID())).getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("T-RATE·SEC-RATE-08: 선언 없는 API는 계정마다 기본 한도")
    void limitsOtherApisPerAccount() throws Exception {
        UUID account = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            assertThat(perform(asAccount(get("/api/me/home"), account)).getStatus()).isNotEqualTo(429);
        }

        assertLimited(perform(asAccount(get("/api/me/home"), account)));
        assertThat(perform(asAccount(get("/api/me/home"), UUID.randomUUID())).getStatus()).isNotEqualTo(429);
    }

    @Test
    @DisplayName("T-RATE·SEC-RATE-08: 로그인 전 API는 IP마다 기본 한도")
    void limitsAnonymousApisPerIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(perform(fromIp(get("/api/auth/session"), "198.51.100.30")).getStatus()).isEqualTo(200);
        }

        assertLimited(perform(fromIp(get("/api/auth/session"), "198.51.100.30")));
        assertThat(perform(fromIp(get("/api/auth/session"), "198.51.100.31")).getStatus()).isEqualTo(200);
    }

    private void assertLimited(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(response.getHeader(HttpHeaders.RETRY_AFTER))).isPositive();
        assertThat(jsonMapper.readTree(response.getContentAsString()).get("code").asString())
                .isEqualTo("RATE_LIMITED");
    }

    private static MockHttpServletRequestBuilder createOrg() {
        return post("/api/orgs").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    private static MockHttpServletRequestBuilder fromIp(MockHttpServletRequestBuilder request, String address) {
        return request.with(servlet -> {
            servlet.setRemoteAddr(address);
            return servlet;
        });
    }

    private static MockHttpServletRequestBuilder asAccount(MockHttpServletRequestBuilder request, UUID account) {
        return request.with(authentication(new AccountAuthentication(account, Instant.now())));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(csrfToken())).andReturn().getResponse();
    }

}
