package reberu.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static reberu.TestCsrf.csrfToken;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import reberu.TestcontainersConfiguration;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;
import reberu.core.auth.security.AccountAuthentication;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"reberu.rate-limit.failure-streaks.invite-link.threshold=3",
        "reberu.tenancy.invite-link-failure-delay=0ms"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InviteLinkStreakTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    Accounts accounts;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    @DisplayName("SEC-RATE-02: 같은 IP에서 실패가 연속 한도에 닿으면 링크 판정 없이 429와 Retry-After, 다른 IP는 그대로")
    void blocksIpAfterConsecutiveFailures() throws Exception {
        UUID account = accounts.createWithConsent(new GoogleProfile("sub-streak-" + UUID.randomUUID(),
                "streak@example.com", "streak", null, null), "v1", "v1", Instant.now());

        for (int i = 0; i < 3; i++) {
            assertThat(redeem(account, "198.51.100.20").getStatus()).isEqualTo(404);
        }
        MockHttpServletResponse blocked = redeem(account, "198.51.100.20");

        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(jsonMapper.readTree(blocked.getContentAsString()).get("code").asString())
                .isEqualTo("RATE_LIMITED");
        assertThat(Integer.parseInt(blocked.getHeader("Retry-After"))).isBetween(899, 900);
        assertThat(redeem(account, "198.51.100.21").getStatus()).isEqualTo(404);
    }

    private MockHttpServletResponse redeem(UUID account, String address) throws Exception {
        return mockMvc.perform(post("/api/invite-links/redeem").with(request -> {
            request.setRemoteAddr(address);
            return request;
        }).with(authentication(new AccountAuthentication(account, Instant.now()))).with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("token", "B".repeat(43))))).andReturn().getResponse();
    }

}
