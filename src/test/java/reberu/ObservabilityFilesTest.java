package reberu;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ObservabilityFilesTest {

    private static final Path DASHBOARDS = Path.of("ops/grafana/dashboards");

    @Test
    @DisplayName("OPS-OBS-05: Grafana 비밀번호는 환경 변수로만, 빈 값·기본 비밀번호면 시작 거부, 익명 접근·가입 끔")
    void grafanaStartsOnlyWithOwnPassword() throws IOException {
        Map<String, Object> grafana = service("grafana");
        Map<String, Object> environment = map(grafana.get("environment"));
        String rawCompose = Files.readString(Path.of("compose.yml"));
        String entrypoint = String.join("\n", strings(grafana.get("entrypoint")));

        assertThat(rawCompose).contains("GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD:-}");
        assertThat(environment.get("GF_AUTH_ANONYMOUS_ENABLED")).isEqualTo("false");
        assertThat(environment.get("GF_USERS_ALLOW_SIGN_UP")).isEqualTo("false");
        assertThat(environment.keySet()).noneMatch(key -> key.startsWith("GF_AUTH_ANONYMOUS_ORG"));
        assertThat(entrypoint).contains("-z \"$$GF_SECURITY_ADMIN_PASSWORD\"",
                "\"$$GF_SECURITY_ADMIN_PASSWORD\" = \"admin\"", "exit 1", "exec /run.sh");
        assertThat(entrypoint.indexOf("exit 1")).isLessThan(entrypoint.indexOf("exec /run.sh"));
        assertThat(Files.readString(Path.of(".env.example"))).contains("# GRAFANA_ADMIN_PASSWORD=\n");
    }

    @Test
    @DisplayName("OPS-OBS-06: Prometheus·Grafana는 선택 프로필이고 화면 포트는 루프백에만 공개")
    void observabilityToolsAreOptionalAndLocalOnly() throws IOException {
        for (String name : List.of("prometheus", "grafana")) {
            Map<String, Object> service = service(name);

            assertThat(strings(service.get("profiles"))).as(name).containsExactly("observability");
            assertThat(strings(service.get("ports"))).as(name).isNotEmpty()
                    .allMatch(port -> port.startsWith("127.0.0.1:"));
            assertThat(service.get("image").toString()).as(name).matches("[a-z/]+:v?\\d+\\.\\d+\\.\\d+");
        }
        assertThat(service("postgres")).doesNotContainKey("profiles");
    }

    @Test
    @DisplayName("OPS-OBS-01: Prometheus는 관리 포트의 지표 끝점만 수집")
    void prometheusScrapesTheManagementPort() throws IOException {
        Map<String, Object> config = yaml(Path.of("ops/prometheus/prometheus.yml"));
        List<?> jobs = (List<?>) config.get("scrape_configs");
        Map<String, Object> job = map(jobs.get(0));

        assertThat(jobs).hasSize(1);
        assertThat(job.get("metrics_path")).isEqualTo("/actuator/prometheus");
        assertThat(strings(map(((List<?>) job.get("static_configs")).get(0)).get("targets")))
                .containsExactly("host.docker.internal:8081");
        assertThat(Files.readString(Path.of("src/main/resources/application.yml")))
                .contains("port: ${MANAGEMENT_PORT:8081}", "address: ${MANAGEMENT_ADDRESS:127.0.0.1}");
    }

    @Test
    @DisplayName("OPS-OBS-09: 데이터 소스와 대시보드는 파일로 두고 Grafana가 시작할 때 읽음")
    void dashboardsAreProvisionedFromFiles() throws IOException {
        Map<String, Object> grafana = service("grafana");
        Map<String, Object> source = map(((List<?>) yaml(Path.of(
                "ops/grafana/provisioning/datasources/prometheus.yml")).get("datasources")).get(0));
        Map<String, Object> provider = map(((List<?>) yaml(Path.of(
                "ops/grafana/provisioning/dashboards/dashboards.yml")).get("providers")).get(0));
        JsonNode dashboard = JsonMapper.builder().build()
                .readTree(Files.readString(DASHBOARDS.resolve("reberu-overview.json")));
        String expressions = String.join("\n", dashboard.findValuesAsString("expr"));

        assertThat(strings(grafana.get("volumes"))).contains(
                "./ops/grafana/provisioning:/etc/grafana/provisioning:ro",
                "./ops/grafana/dashboards:/var/lib/grafana/dashboards:ro");
        assertThat(source.get("uid")).isEqualTo("prometheus");
        assertThat(source.get("url")).isEqualTo("http://prometheus:9090");
        assertThat(map(provider.get("options")).get("path")).isEqualTo("/var/lib/grafana/dashboards");
        assertThat(dashboard.findValuesAsString("uid")).contains("reberu-overview").containsOnly("reberu-overview",
                "prometheus");
        assertThat(expressions).contains("http_server_requests_seconds", "jvm_memory_used_bytes",
                "hikaricp_connections_pending", "reberu_events_incomplete_oldest_age_seconds",
                "reberu_events_listener_failures_total", "reberu_security_rejections_total",
                "reberu_security_rate_limited_total");
    }

    private static Map<String, Object> service(String name) throws IOException {
        return map(map(yaml(Path.of("compose.yml")).get("services")).get(name));
    }

    private static Map<String, Object> yaml(Path path) throws IOException {
        return new Yaml().load(Files.readString(path));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static List<String> strings(Object value) {
        return ((List<?>) value).stream().map(Object::toString).toList();
    }

}
