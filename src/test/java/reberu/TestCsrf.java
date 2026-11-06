package reberu;

import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

public final class TestCsrf {

    private static final String TOKEN = "test-csrf-token";

    private TestCsrf() {
    }

    public static RequestPostProcessor csrfToken() {
        return request -> {
            request.setCookies(new Cookie("XSRF-TOKEN", TOKEN));
            request.addHeader("X-XSRF-TOKEN", TOKEN);
            return request;
        };
    }

}
