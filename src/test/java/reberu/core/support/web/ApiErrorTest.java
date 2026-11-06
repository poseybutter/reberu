package reberu.core.support.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reberu.TestcontainersConfiguration;
import reberu.core.support.ErrorCode;
import reberu.core.support.ErrorResponseWriter;
import reberu.core.support.ReberuException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
@Import({TestcontainersConfiguration.class, ApiErrorTest.ProbeController.class})
class ApiErrorTest {

    private static final String SECRET_DETAIL = "SELECT password FROM account WHERE id = 7 at reberu.core.Hidden";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    @DisplayName("업무 예외: 정해진 코드·문구, 400 밖에는 fields 없음")
    void reberuExceptionUsesItsCode() throws Exception {
        JsonNode body = error(get("/api/probe/conflict"), 409);

        assertThat(body.get("code").asString()).isEqualTo("LAST_ADMIN_REQUIRED");
        assertThat(body.get("message").asString()).isEqualTo(ErrorCode.LAST_ADMIN_REQUIRED.message());
        assertThat(body.has("fields")).isFalse();
    }

    @Test
    @DisplayName("예상하지 못한 예외: 500 INTERNAL_ERROR, 원인 정보 미노출")
    void unexpectedExceptionHidesDetails() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/probe/boom")).andReturn();
        String raw = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(jsonMapper.readTree(raw).get("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(raw).doesNotContain("SELECT", "password", "reberu.", "IllegalStateException", "Exception", "at ");
        assertOnlyAllowedKeys(jsonMapper.readTree(raw), false);
    }

    @Test
    @DisplayName("검증 실패: 400 INVALID_REQUEST, 칸별 사유 코드, 입력 값 미반환")
    void validationFailureListsFieldReasons() throws Exception {
        String request = """
                {"name": "가", "code": "abc-<script>", "nickname": "%s"}
                """.formatted("다".repeat(11));

        MvcResult result = mockMvc.perform(post("/api/probe/body").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andReturn();
        JsonNode body = jsonMapper.readTree(result.getResponse().getContentAsString());

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body.get("code").asString()).isEqualTo("INVALID_REQUEST");
        assertThat(fieldReasons(body)).containsExactlyInAnyOrder(
                "name:TOO_SHORT", "code:INVALID_FORMAT", "nickname:TOO_LONG");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("<script>", "다다다");
        assertOnlyAllowedKeys(body, true);
    }

    @Test
    @DisplayName("빈 필수 값: REQUIRED")
    void blankRequiredFieldIsRequired() throws Exception {
        JsonNode body = error(post("/api/probe/body").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \" \", \"code\": \"ab\"}"), 400);

        assertThat(fieldReasons(body)).containsExactly("name:REQUIRED");
    }

    @Test
    @DisplayName("DTO에 없는 필드·깨진 JSON·지원하지 않는 Content-Type: 400")
    void malformedRequestsAreInvalid() throws Exception {
        JsonNode unknownField = error(post("/api/probe/body").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"다온\", \"code\": \"ab\", \"teamId\": \"x\"}"), 400);
        JsonNode brokenJson = error(post("/api/probe/body").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": "), 400);
        JsonNode textBody = error(post("/api/probe/body").with(csrf()).contentType(MediaType.TEXT_PLAIN)
                .content("name=다온"), 400);

        for (JsonNode body : List.of(unknownField, brokenJson, textBody)) {
            assertThat(body.get("code").asString()).isEqualTo("INVALID_REQUEST");
            assertThat(body.get("fields")).isEmpty();
            assertOnlyAllowedKeys(body, true);
        }
    }

    @Test
    @DisplayName("쿼리 값 타입 오류·범위 위반: 400")
    void invalidQueryParametersAreInvalid() throws Exception {
        JsonNode typeMismatch = error(get("/api/probe/limit").param("limit", "abc"), 400);
        JsonNode outOfRange = error(get("/api/probe/limit").param("limit", "101"), 400);

        assertThat(typeMismatch.get("code").asString()).isEqualTo("INVALID_REQUEST");
        assertThat(fieldReasons(outOfRange)).containsExactly("limit:NOT_ALLOWED_VALUE");
    }

    @Test
    @DisplayName("모르는 /api 경로·허용하지 않는 메서드: JSON 404")
    void unknownApiPathIsJsonNotFound() throws Exception {
        JsonNode unknown = error(get("/api/does-not-exist"), 404);
        JsonNode wrongMethod = error(put("/api/probe/conflict").with(csrf()), 404);

        assertThat(unknown.get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(wrongMethod.get("code").asString()).isEqualTo("NOT_FOUND");
        assertOnlyAllowedKeys(unknown, false);
    }

    @Test
    @DisplayName("API 응답 버전 헤더 X-App-Version·X-App-Min-Version")
    void apiResponsesCarryVersionHeaders() throws Exception {
        MvcResult ok = mockMvc.perform(get("/api/probe/ok")).andReturn();
        MvcResult notFound = mockMvc.perform(get("/api/does-not-exist")).andReturn();

        for (MvcResult result : List.of(ok, notFound)) {
            assertThat(result.getResponse().getHeader(AppVersionHeaderFilter.VERSION)).isEqualTo("0.0.0");
            assertThat(result.getResponse().getHeader(AppVersionHeaderFilter.MIN_VERSION)).isEqualTo("0.0.0");
        }
    }

    @Test
    @DisplayName("요청마다 다른 16자리 traceId")
    void traceIdIsSixteenHexPerRequest() throws Exception {
        String first = error(get("/api/probe/conflict"), 409).get("traceId").asString();
        String second = error(get("/api/probe/conflict"), 409).get("traceId").asString();

        assertThat(first).matches("[0-9a-f]{16}");
        assertThat(second).matches("[0-9a-f]{16}").isNotEqualTo(first);
    }

    @Test
    @DisplayName("오류 페이지 /error: 상태별 코드, Boot 기본 항목 미노출")
    void errorPageUsesSameBody() throws Exception {
        JsonNode serverError = error(get("/error")
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                .requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException(SECRET_DETAIL)), 500);
        JsonNode notFound = error(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404), 404);

        assertThat(serverError.get("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(notFound.get("code").asString()).isEqualTo("NOT_FOUND");
        assertOnlyAllowedKeys(serverError, false);
        assertThat(serverError.toString()).doesNotContain("SELECT", "path", "timestamp");
    }

    @Test
    @DisplayName("필터 단계 오류도 같은 본문")
    void writerUsesSameBody() throws Exception {
        JsonNode body = error(get("/api/probe/written"), 429);

        assertThat(body.get("code").asString()).isEqualTo("RATE_LIMITED");
        assertOnlyAllowedKeys(body, false);
    }

    @Test
    @DisplayName("권한 예외는 Spring Security에 넘김")
    void accessDeniedIsLeftToSecurity() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/probe/denied")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("INTERNAL_ERROR");
    }

    private JsonNode error(RequestBuilder request, int status) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private static void assertOnlyAllowedKeys(JsonNode body, boolean withFields) {
        List<String> expected = new ArrayList<>(List.of("code", "message", "traceId"));
        if (withFields) {
            expected.add("fields");
        }
        assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static List<String> fieldReasons(JsonNode body) {
        List<String> reasons = new ArrayList<>();
        body.get("fields").forEach(field -> {
            assertThat(field.propertyNames()).containsExactlyInAnyOrder("field", "reason");
            reasons.add(field.get("field").asString() + ":" + field.get("reason").asString());
        });
        return reasons;
    }

    record ProbeBody(
            @NotBlank @Size(min = 2, max = 10) String name,
            @Pattern(regexp = "[a-z]{2,5}") String code,
            @Size(max = 10) String nickname
    ) {
    }

    @RestController
    @RequestMapping("/api/probe")
    static class ProbeController {

        private final ErrorResponseWriter writer;

        ProbeController(ErrorResponseWriter writer) {
            this.writer = writer;
        }

        @GetMapping("/ok")
        ResponseEntity<Void> ok() {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/conflict")
        void conflict() {
            throw new ReberuException(ErrorCode.LAST_ADMIN_REQUIRED);
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException(SECRET_DETAIL);
        }

        @PostMapping("/body")
        ResponseEntity<Void> body(@Valid @RequestBody ProbeBody body) {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/limit")
        ResponseEntity<Void> limit(@RequestParam @Max(100) int limit) {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/written")
        void written(HttpServletRequest request, HttpServletResponse response) throws IOException {
            writer.write(request, response, ErrorCode.RATE_LIMITED);
        }

        @GetMapping("/denied")
        void denied() {
            throw new AccessDeniedException("denied");
        }

    }

}
