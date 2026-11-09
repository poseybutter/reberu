package reberu.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SensitiveTextTest {

    @Test
    @DisplayName("이메일은 첫 글자와 도메인만 남기고 가림")
    void masksEmail() {
        String masked = SensitiveText.mask("초대 보냄: dana.kim@example.com, ok@sub.example.org");

        assertThat(masked).isEqualTo("초대 보냄: d***@example.com, o***@sub.example.org");
    }

    @Test
    @DisplayName("이미 가린 값은 다시 가려도 그대로")
    void maskIsIdempotent() {
        String once = SensitiveText.mask("dana@example.com");

        assertThat(SensitiveText.mask(once)).isEqualTo(once);
    }

    @Test
    @DisplayName("URL의 쿼리와 조각은 가리고 경로는 남김")
    void masksUrlQuery() {
        String masked = SensitiveText.mask("발송 실패 https://chat.example.com/v1/spaces/abc/messages?key=k&token=t#x 끝");

        assertThat(masked).isEqualTo("발송 실패 https://chat.example.com/v1/spaces/abc/messages?*** 끝");
    }

    @Test
    @DisplayName("숫자와 글자가 섞인 32자 이상 문자열은 토큰으로 보고 가림")
    void masksTokens() {
        String hash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
        String jwtPart = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9";

        String masked = SensitiveText.mask("hash=" + hash + " jwt " + jwtPart + ".payload");

        assertThat(masked).isEqualTo("hash=*** jwt ***.payload");
    }

    @Test
    @DisplayName("UUID와 긴 클래스 이름은 토큰으로 보지 않음")
    void keepsUuidAndIdentifiers() {
        String text = "account 0190f2a4-0000-7000-8000-000000000001 ServletWebServerFactoryAutoConfiguration";

        assertThat(SensitiveText.mask(text)).isEqualTo(text);
        assertThat(SensitiveText.containsSensitive(text)).isFalse();
    }

    @Test
    @DisplayName("줄바꿈은 글자로 바꿔 가짜 로그 줄을 막음")
    void escapesLineBreaks() {
        String escaped = SensitiveText.escapeLineBreaks("로그인 실패\r\n2026-11-09 INFO 로그인 성공");

        assertThat(escaped).isEqualTo("로그인 실패\\r\\n2026-11-09 INFO 로그인 성공").doesNotContain("\n", "\r");
    }

    @Test
    @DisplayName("이메일·URL·토큰·제어 문자 검출")
    void detectsSensitiveValues() {
        assertThat(SensitiveText.containsSensitive("a@example.com")).isTrue();
        assertThat(SensitiveText.containsSensitive("javascript://x")).isTrue();
        assertThat(SensitiveText.containsSensitive("Ab3".repeat(11))).isTrue();
        assertThat(SensitiveText.containsSensitive("TEAM_MEMBER")).isFalse();
        assertThat(SensitiveText.containsControl("a\u0000b")).isTrue();
        assertThat(SensitiveText.containsControl("평범한 값")).isFalse();
    }

}
