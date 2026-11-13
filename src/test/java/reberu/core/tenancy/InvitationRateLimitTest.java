package reberu.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static reberu.TestCsrf.csrfToken;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import reberu.TestcontainersConfiguration;
import reberu.core.account.Accounts;
import reberu.core.account.GoogleProfile;
import reberu.core.auth.security.AccountAuthentication;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "reberu.rate-limit.rules.invitation.limit=5")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InvitationRateLimitTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Accounts accounts;

    @Autowired
    JsonMapper jsonMapper;

    private UUID admin;
    private String orgId;

    @BeforeEach
    void setUp() throws Exception {
        for (String table : InvitationTest.TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        admin = accounts.createWithConsent(new GoogleProfile("sub-limit-" + UUID.randomUUID(), "limit@example.com",
                "limit", null, null), "v1", "v1", Instant.now());
        Map<String, Object> org = new LinkedHashMap<>();
        org.put("name", "제한 확인");
        org.put("visibility", "PRIVATE");
        org.put("timeZone", "Asia/Seoul");
        org.put("firstTeam", Map.of("name", "첫 팀", "jobTypeKey", "basic"));
        String response = perform(post("/api/orgs").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(org))).getContentAsString();
        orgId = jsonMapper.readTree(response).get("orgId").asString();
    }

    @Test
    @DisplayName("SEC-RATE-03·15: 이메일 초대 한도는 요청 수가 아니라 이메일 수로 세고, 넘친 요청의 이메일 수는 세지 않음")
    void countsEmails() throws Exception {
        assertThat(create(List.of("a@example.com", "b@example.com", "c@example.com"))).isEqualTo(201);
        assertThat(create(List.of("d@example.com", "e@example.com", "f@example.com"))).isEqualTo(429);
        assertThat(create(List.of("g@example.com"))).isEqualTo(201);
        assertThat(create(List.of("h@example.com"))).isEqualTo(429);

        assertThat(jdbc.sql("SELECT email FROM invitation ORDER BY email").query(String.class).list())
                .containsExactly("a@example.com", "b@example.com", "c@example.com", "g@example.com");
    }

    private int create(List<String> emails) throws Exception {
        return perform(post("/api/orgs/" + orgId + "/invitations")
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(InvitationTest.invite(emails, "MEMBER", null, null, null))))
                .getStatus();
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request.with(authentication(new AccountAuthentication(admin, Instant.now())))
                .with(csrfToken())).andReturn().getResponse();
    }

}
