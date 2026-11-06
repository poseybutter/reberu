package reberu.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UuidsTest {

    @Test
    @DisplayName("UUID v7: 버전 7·변형 2, 앞 48비트는 밀리초 시각")
    void v7CarriesVersionVariantAndTime() {
        long millis = 1_793_000_000_000L;

        UUID uuid = Uuids.v7(millis);

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
        assertThat(uuid.getMostSignificantBits() >>> 16).isEqualTo(millis);
        assertThat(uuid.toString()).matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("시각 순서대로 정렬, 같은 시각에도 겹치지 않음")
    void v7IsOrderedAndUnique() {
        assertThat(Uuids.v7(1000L).toString()).isLessThan(Uuids.v7(1001L).toString());

        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(Uuids.v7(1000L));
        }
        assertThat(seen).hasSize(10_000);
    }

}
