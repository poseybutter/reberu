package reberu.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import reberu.TestcontainersConfiguration;
import reberu.core.auth.security.AccountAuthentication;
import reberu.core.tenancy.service.TenancyProperties;

@SpringBootTest(properties = "reberu.tenancy.mode=")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BlankDeploymentModeTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TenancyProperties properties;

    @Test
    @DisplayName("배포 모드가 빈 값이면 MULTI_ORG로 동작하고 조직 API 공개")
    void blankModeMeansMultiOrg() throws Exception {
        assertThat(properties.mode()).isEqualTo(DeploymentMode.MULTI_ORG);
        assertThat(mockMvc.perform(get("/api/default-job-types")
                        .with(authentication(new AccountAuthentication(UUID.randomUUID(), Instant.now()))))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

}
