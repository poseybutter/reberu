package reberu.core.support.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import reberu.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class LogMaskingTest {

    private static final Logger log = LoggerFactory.getLogger(LogMaskingTest.class);

    private static final String EMAIL = "dana.kim@example.com";
    private static final String TOKEN = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    @Test
    @DisplayName("T-LOG-MASK: 로그 메시지의 이메일·토큰을 가림")
    void masksMessage(CapturedOutput output) {
        log.info("초대 대상 {} 토큰 {}", EMAIL, TOKEN);

        assertThat(output.getOut())
                .contains("초대 대상 d***@example.com 토큰 ***")
                .doesNotContain(EMAIL, TOKEN);
    }

    @Test
    @DisplayName("T-LOG-MASK: 예외 메시지와 원인 예외의 이메일·토큰을 가림")
    void masksException(CapturedOutput output) {
        Exception cause = new IllegalStateException("원인 " + TOKEN);

        log.error("처리 실패", new IllegalArgumentException("대상 " + EMAIL, cause));

        assertThat(output.getOut())
                .contains("대상 d***@example.com", "원인 ***")
                .doesNotContain(EMAIL, TOKEN);
    }

    @Test
    @DisplayName("T-LOG-MASK: 메시지의 줄바꿈으로 가짜 로그 줄을 만들 수 없음")
    void blocksForgedLines(CapturedOutput output) {
        log.info("로그인 실패: {}", "x\n2026-11-09T00:00:00.000Z  INFO 위조된 줄");

        assertThat(output.getOut())
                .contains("로그인 실패: x\\n2026-11-09T00:00:00.000Z  INFO 위조된 줄")
                .doesNotContainPattern("(?m)^2026-11-09T00:00:00.000Z  INFO 위조된 줄");
    }

}
