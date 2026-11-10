package reberu.core.support.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import reberu.TestcontainersConfiguration;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, CsrfTest.ProbeController.class})
class CsrfTest {

    private static final String PATH = "/api/csrf-probe";
    private static final String COOKIE = "XSRF-TOKEN";
    private static final String HEADER = "X-XSRF-TOKEN";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    @DisplayName("토큰 없는 POST·PUT·PATCH·DELETE: 403 CSRF_INVALID")
    void changingRequestsWithoutTokenAreRejected() throws Exception {
        for (MockHttpServletRequestBuilder request : changingRequests()) {
            MockHttpServletResponse response = perform(request.with(user("member")), 403);

            assertThat(code(response)).isEqualTo("CSRF_INVALID");
        }
    }

    @Test
    @DisplayName("쿠키와 다른 토큰·쿠키만 있고 헤더 없음: 403 CSRF_INVALID")
    void mismatchedTokenIsRejected() throws Exception {
        String token = issuedToken();

        MockHttpServletResponse wrongHeader = perform(post(PATH).with(user("member"))
                .cookie(new Cookie(COOKIE, token)).header(HEADER, token + "x"), 403);
        MockHttpServletResponse cookieOnly = perform(post(PATH).with(user("member"))
                .cookie(new Cookie(COOKIE, token)), 403);
        MockHttpServletResponse headerOnly = perform(post(PATH).with(user("member"))
                .header(HEADER, token), 403);

        for (MockHttpServletResponse response : List.of(wrongHeader, cookieOnly, headerOnly)) {
            assertThat(code(response)).isEqualTo("CSRF_INVALID");
        }
    }

    @Test
    @DisplayName("쿠키와 같은 토큰을 헤더로 보내면 통과")
    void matchingTokenPasses() throws Exception {
        String token = issuedToken();

        for (MockHttpServletRequestBuilder request : changingRequests()) {
            perform(request.with(user("member")).cookie(new Cookie(COOKIE, token)).header(HEADER, token), 204);
        }
    }

    @Test
    @DisplayName("CSRF 쿠키: 화면에서 읽을 수 있고 Secure·SameSite=Lax")
    void tokenCookieIsReadableAndSecure() throws Exception {
        Cookie cookie = perform(get(PATH).with(user("member")), 204).getCookie(COOKIE);

        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isFalse();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/");
    }

    @Test
    @DisplayName("로그인하지 않은 요청에도 CSRF 쿠키 발급")
    void tokenCookieIsIssuedBeforeLogin() throws Exception {
        assertThat(perform(get(PATH), 401).getCookie(COOKIE)).isNotNull();
    }

    @Test
    @DisplayName("로그인하지 않은 변경 요청: 세션 확인이 먼저라 401 UNAUTHENTICATED")
    void unauthenticatedChangingRequestIsUnauthenticated() throws Exception {
        MockHttpServletResponse withoutToken = perform(post(PATH), 401);
        String token = issuedToken();
        MockHttpServletResponse withToken = perform(post(PATH)
                .cookie(new Cookie(COOKIE, token)).header(HEADER, token), 401);

        assertThat(code(withoutToken)).isEqualTo("UNAUTHENTICATED");
        assertThat(code(withToken)).isEqualTo("UNAUTHENTICATED");
    }

    private String issuedToken() throws Exception {
        Cookie cookie = perform(get(PATH).with(user("member")), 204).getCookie(COOKIE);
        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    private static List<MockHttpServletRequestBuilder> changingRequests() {
        return List.of(post(PATH), put(PATH), patch(PATH), delete(PATH));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, int status) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        return response;
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return jsonMapper.readTree(response.getContentAsString()).get("code").asString();
    }

    @RestController
    @RequestMapping(PATH)
    static class ProbeController {

        @GetMapping

        @Access(AccessLevel.ACCOUNT)
        ResponseEntity<Void> read() {
            return ResponseEntity.noContent().build();
        }

        @RequestMapping(method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})

        @Access(AccessLevel.ACCOUNT)
        ResponseEntity<Void> change() {
            return ResponseEntity.noContent().build();
        }

    }

}
