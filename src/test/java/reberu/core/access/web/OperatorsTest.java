package reberu.core.access.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OperatorsTest {

    private static final UUID FIRST = UUID.fromString("00000000-0000-7000-8000-00000000a001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-7000-8000-00000000a002");

    @Test
    @DisplayName("TEN-OPS-01: 목록에 있는 계정만 운영자, 빈 값이면 운영자 없음")
    void recognizesListedAccounts() {
        Operators operators = Operators.parse(Arrays.asList(" " + FIRST + " ", "", null,
                SECOND.toString().toUpperCase()));

        assertThat(operators.contains(FIRST)).isTrue();
        assertThat(operators.contains(SECOND)).isTrue();
        assertThat(operators.contains(UUID.randomUUID())).isFalse();
        assertThat(operators.contains(null)).isFalse();
        assertThat(Operators.parse(List.of()).contains(FIRST)).isFalse();
        assertThat(Operators.parse(null).contains(FIRST)).isFalse();
        assertThat(Operators.parse(List.of("")).contains(FIRST)).isFalse();
    }

    @Test
    @DisplayName("TEN-OPS-01: 계정 UUID가 아닌 값이 있으면 시작 실패, 메시지에 값 미포함")
    void rejectsMalformedIds() {
        for (String value : List.of("operator@example.com", "1-1-1-1-1", FIRST + "0",
                "zzzzzzzz-0000-7000-8000-00000000a001", "100000000-000-7000-8000-00000000a001",
                "0000000-00000-7000-8000-00000000a001")) {
            assertThatThrownBy(() -> Operators.parse(List.of(FIRST.toString(), value)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("OPERATOR_ACCOUNT_IDS")
                    .hasMessageNotContaining(value);
        }
    }

}
