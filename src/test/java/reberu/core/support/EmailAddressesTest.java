package reberu.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmailAddressesTest {

    @Test
    @DisplayName("SEC-TOKEN-09: 앞뒤 공백 제거와 전체 소문자만, 점·+ 꼬리는 그대로")
    void normalizesOnlyCaseAndSpace() {
        assertThat(EmailAddresses.normalize("  Dana.Kim+Work@Example.COM\t")).isEqualTo("dana.kim+work@example.com");
        assertThat(EmailAddresses.normalize("danakim@example.com"))
                .isNotEqualTo(EmailAddresses.normalize("dana.kim@example.com"));
    }

    @Test
    @DisplayName("SEC-TOKEN-09: ASCII 밖 문자·공백·제어 문자·@ 개수·도메인 형식·길이가 맞지 않으면 받지 않음")
    void rejectsMalformed() {
        assertThat(EmailAddresses.valid("dana@example.com")).isTrue();
        assertThat(EmailAddresses.valid("a@b.co")).isTrue();
        for (String email : new String[] {"", "다나@example.com", "dana@exämple.com", "da na@example.com",
                "dana\u0000@example.com", "@example.com", "dana@", "dana@example", "dana@.example.com",
                "dana@example.com.", "dana@example..com", "a@b@example.com", "x".repeat(65) + "@example.com",
                "a@" + "b".repeat(250) + ".com"}) {
            assertThat(EmailAddresses.valid(email)).as(email).isFalse();
        }
    }

}
