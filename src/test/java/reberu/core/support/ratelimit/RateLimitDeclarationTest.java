package reberu.core.support.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import reberu.TestcontainersConfiguration;
import reberu.core.support.RateLimit;
import reberu.core.support.RateLimited;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RateLimitDeclarationTest {

    private static final Path API_DOC = Path.of("docs/architecture/api.md");
    private static final Pattern ROW = Pattern.compile("^\\| `(GET|POST|PUT|PATCH|DELETE)` \\| `(/[^`]*)`.*$");
    private static final Pattern RULE = Pattern.compile("제한 (SEC-RATE-0[1-8])");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("T-RATE: api.md의 제한 표시와 @RateLimited 일치, 표시 없으면 SEC-RATE-08")
    void declarationsMatchApiDocument() throws IOException {
        Map<String, String> documented = documentedRules();
        List<String> mismatches = new ArrayList<>();
        int declared = 0;

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RateLimited annotation = entry.getValue().getMethodAnnotation(RateLimited.class);
            RateLimit rule = annotation == null ? RateLimit.DEFAULT : annotation.value();
            if (annotation != null) {
                declared++;
            }
            for (String pattern : entry.getKey().getPatternValues()) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                for (RequestMethod method : entry.getKey().getMethodsCondition().getMethods()) {
                    String key = method.name() + " " + normalize(pattern);
                    String expected = documented.getOrDefault(key, RateLimit.DEFAULT.id());
                    if (!expected.equals(rule.id())) {
                        mismatches.add(key + ": 문서 " + expected + ", 코드 " + rule.id());
                    }
                }
            }
        }

        assertThat(declared).isPositive();
        assertThat(mismatches).as("api.md 제한 표시와 @RateLimited 불일치").isEmpty();
    }

    @Test
    @DisplayName("T-RATE: IP 기준 규칙은 SEC-RATE-01·02뿐(security.md §13.2)")
    void keyTypesMatchSecurityDocument() {
        assertThat(List.of(RateLimit.values())).filteredOn(rule -> rule.key() == RateLimit.Key.IP)
                .containsExactly(RateLimit.LOGIN, RateLimit.INVITE_LINK);
    }

    @Test
    @DisplayName("api.md 표 읽기: 조직 만들기의 제한 표시")
    void parsesApiDocument() throws IOException {
        assertThat(documentedRules()).containsEntry("POST /api/orgs", "SEC-RATE-05")
                .containsEntry("POST /api/invite-links/redeem", "SEC-RATE-02");
    }

    private static Map<String, String> documentedRules() throws IOException {
        Map<String, String> rules = new HashMap<>();
        for (String line : Files.readAllLines(API_DOC, StandardCharsets.UTF_8)) {
            Matcher row = ROW.matcher(line);
            if (!row.matches()) {
                continue;
            }
            Matcher rule = RULE.matcher(line);
            if (rule.find()) {
                rules.put(row.group(1) + " " + normalize(row.group(2)), rule.group(1));
            }
        }
        return rules;
    }

    private static String normalize(String path) {
        return path.replaceAll("\\{[^}]*}", "{}");
    }

}
