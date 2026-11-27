package reberu.core.event.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reberu.core.event.delivery.EventDeliveryProperties.Retry;

class EventDeliveryPropertiesTest {

    private static final Retry RETRY = new Retry(List.of(Duration.ofMinutes(1)), Duration.ofSeconds(30));

    @Test
    @DisplayName("ARCH-EVT-04: 재시도 간격이 없거나 0 이하면 시작 실패")
    void rejectsInvalidIntervals() {
        assertThatThrownBy(() -> new Retry(List.of(), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("재시도 간격");
        assertThatThrownBy(() -> new Retry(null, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("재시도 간격");
        assertThatThrownBy(() -> new Retry(List.of(Duration.ofMinutes(1), Duration.ZERO), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("재시도 간격");
        assertThatThrownBy(() -> new Retry(List.of(Duration.ofMinutes(1)), Duration.ZERO))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("확인 간격");
        assertThatThrownBy(() -> new Retry(List.of(Duration.ofMinutes(1)), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("확인 간격");
    }

    @Test
    @DisplayName("ARCH-EVT-08: 보관 기간이 없거나 1일보다 짧으면 시작 실패")
    void rejectsShortRetention() {
        assertThatThrownBy(() -> new EventDeliveryProperties(RETRY, Duration.ofHours(23)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("보관 기간");
        assertThatThrownBy(() -> new EventDeliveryProperties(RETRY, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("보관 기간");
        assertThatThrownBy(() -> new EventDeliveryProperties(null, Duration.ofDays(30)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("재시도 설정");

        assertThat(new EventDeliveryProperties(RETRY, Duration.ofDays(1)).retention()).isEqualTo(Duration.ofDays(1));
    }

}
