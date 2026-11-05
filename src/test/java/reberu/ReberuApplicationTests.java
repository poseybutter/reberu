package reberu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReberuApplicationTests {

    @Test
    @DisplayName("애플리케이션 컨텍스트 로딩")
    void contextLoads() {
    }

}
