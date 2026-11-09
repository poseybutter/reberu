package reberu.core.auth;

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
import reberu.core.support.RequiresRecentAuth;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EndpointDeclarationTest {

    private static final Path API_DOC = Path.of("docs/architecture/api.md");
    private static final Pattern ROW = Pattern.compile("^\\| `(GET|POST|PUT|PATCH|DELETE)` \\|(.+)$");
    private static final Pattern PATH = Pattern.compile("`(/[^`]*)`");
    private static final String RECENT_AUTH_MARK = "\uD83D\uDD12";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("모든 /api 엔드포인트가 api.md에 있고, 최근 인증 표시와 @RequiresRecentAuth가 일치")
    void endpointsMatchApiDocument() throws IOException {
        Map<String, Boolean> documented = documentedEndpoints();
        List<String> missing = new ArrayList<>();
        List<String> recentAuthMismatch = new ArrayList<>();
        int checked = 0;

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            boolean recentAuth = entry.getValue().hasMethodAnnotation(RequiresRecentAuth.class);
            for (String pattern : entry.getKey().getPatternValues()) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                assertThat(entry.getKey().getMethodsCondition().getMethods())
                        .as(pattern + " HTTP 메서드 선언").isNotEmpty();
                for (RequestMethod method : entry.getKey().getMethodsCondition().getMethods()) {
                    String key = method.name() + " " + normalize(pattern);
                    checked++;
                    if (!documented.containsKey(key)) {
                        missing.add(key);
                    } else if (documented.get(key) != recentAuth) {
                        recentAuthMismatch.add(key + (recentAuth ? " (코드에만 최근 인증)" : " (문서에만 최근 인증)"));
                    }
                }
            }
        }

        assertThat(checked).isPositive();
        assertThat(missing).as("api.md에 없는 엔드포인트").isEmpty();
        assertThat(recentAuthMismatch).as("최근 인증 표시와 @RequiresRecentAuth 불일치").isEmpty();
    }

    @Test
    @DisplayName("api.md 표 읽기: 최근 인증 엔드포인트와 짧게 쓴 대안 경로")
    void parsesApiDocument() throws IOException {
        Map<String, Boolean> documented = documentedEndpoints();

        assertThat(documented).containsEntry("POST /api/auth/logout", false)
                .containsEntry("POST /api/me/withdraw", true)
                .containsEntry("POST /api/orgs/{}/members/{}/suspend", true)
                .containsEntry("POST /api/orgs/{}/members/{}/unsuspend", true);
    }

    private static Map<String, Boolean> documentedEndpoints() throws IOException {
        Map<String, Boolean> endpoints = new HashMap<>();
        for (String line : Files.readAllLines(API_DOC, StandardCharsets.UTF_8)) {
            Matcher row = ROW.matcher(line);
            if (!row.matches()) {
                continue;
            }
            String[] cells = row.group(2).split("\\|");
            boolean recentAuth = cells.length > 2 && cells[2].contains(RECENT_AUTH_MARK);
            Matcher paths = PATH.matcher(cells[0]);
            String first = null;
            while (paths.find()) {
                String path = paths.group(1);
                if (first == null) {
                    first = path;
                } else if (!path.startsWith("/api/")) {
                    path = first.substring(0, first.lastIndexOf('/')) + path;
                }
                endpoints.put(row.group(1) + " " + normalize(path), recentAuth);
            }
        }
        return endpoints;
    }

    private static String normalize(String path) {
        return path.replaceAll("\\{[^}]*}", "{}");
    }

}
