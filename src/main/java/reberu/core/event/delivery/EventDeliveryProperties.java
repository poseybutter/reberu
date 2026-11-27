package reberu.core.event.delivery;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("reberu.events")
public record EventDeliveryProperties(Retry retry, Duration retention) {

    public static final Duration MIN_RETENTION = Duration.ofDays(1);

    public EventDeliveryProperties {
        if (retry == null) {
            throw new IllegalStateException("이벤트 재시도 설정 없음");
        }
        if (retention == null || retention.compareTo(MIN_RETENTION) < 0) {
            throw new IllegalStateException("완료된 발행 기록 보관 기간 값 오류(1일 이상 필요)");
        }
    }

    public record Retry(List<Duration> intervals, Duration checkInterval) {

        public Retry {
            if (intervals == null || intervals.isEmpty() || intervals.stream().anyMatch(Retry::notPositive)) {
                throw new IllegalStateException("이벤트 재시도 간격 값 오류(0보다 긴 간격 하나 이상 필요)");
            }
            if (notPositive(checkInterval)) {
                throw new IllegalStateException("이벤트 재시도 확인 간격 값 오류(0보다 길어야 함)");
            }
            intervals = List.copyOf(intervals);
        }

        private static boolean notPositive(Duration value) {
            return value == null || value.isZero() || value.isNegative();
        }

    }

}
