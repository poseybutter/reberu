package reberu.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CursorCodecTest {

    private static final String KEY = Base64.getEncoder()
            .encodeToString("cursor-codec-test-key-0123456789".getBytes(StandardCharsets.US_ASCII));
    private static final String OTHER_KEY = Base64.getEncoder()
            .encodeToString("another-cursor-test-key-98765432".getBytes(StandardCharsets.US_ASCII));
    private static final String SCOPE = "teams/0190f2a4-0000-7000-8000-000000000001/members";

    private final CursorCodec codec = new CursorCodec(KEY);

    @Test
    @DisplayName("만든 커서 되읽기")
    void decodesWhatItEncoded() {
        List<String> position = List.of("2026-11-06T01:02:03.123456Z", "다온:하늘", "");

        String cursor = codec.encode(SCOPE, position);

        assertThat(codec.decode(SCOPE, cursor)).isEqualTo(position);
    }

    @Test
    @DisplayName("내용을 바꾼 커서 거부")
    void rejectsTamperedPayload() {
        String cursor = codec.encode(SCOPE, List.of("10"));
        String[] token = cursor.split("\\.");
        String forged = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new String(Base64.getUrlDecoder().decode(token[0]), StandardCharsets.US_ASCII)
                        .replaceAll(":.*$", ":" + encodePart("99")).getBytes(StandardCharsets.US_ASCII));

        assertInvalid(SCOPE, forged + "." + token[1]);
    }

    @Test
    @DisplayName("다른 키로 서명한 커서 거부")
    void rejectsCursorSignedWithOtherKey() {
        String cursor = new CursorCodec(OTHER_KEY).encode(SCOPE, List.of("10"));

        assertInvalid(SCOPE, cursor);
    }

    @Test
    @DisplayName("다른 경로 범위의 커서 거부")
    void rejectsCursorFromOtherScope() {
        String cursor = codec.encode("teams/0190f2a4-0000-7000-8000-000000000002/members", List.of("10"));

        assertInvalid(SCOPE, cursor);
    }

    @Test
    @DisplayName("형식 오류·빈 값·길이 초과 커서 거부")
    void rejectsMalformedCursor() {
        assertInvalid(SCOPE, null);
        assertInvalid(SCOPE, "");
        assertInvalid(SCOPE, "not-a-cursor");
        assertInvalid(SCOPE, "a.b.c");
        assertInvalid(SCOPE, "%%%.###");
        assertInvalid(SCOPE, "a".repeat(CursorCodec.MAX_LENGTH + 1));
    }

    @Test
    @DisplayName("짧거나 base64가 아닌 서명 키로 시작 실패")
    void rejectsWeakKey() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[31]);

        assertThatThrownBy(() -> new CursorCodec(shortKey)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CursorCodec("not base64!")).isInstanceOf(IllegalStateException.class);
    }

    private void assertInvalid(String scope, String cursor) {
        assertThatThrownBy(() -> codec.decode(scope, cursor))
                .isInstanceOfSatisfying(ReberuException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    private static String encodePart(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

}
