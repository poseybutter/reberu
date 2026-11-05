package reberu;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DatabaseSettingsTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Value("${reberu.test.db:postgresql}")
    String testDb;

    @Test
    @DisplayName("연결 격리 수준 READ COMMITTED")
    void transactionIsolationIsReadCommitted() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        }
    }

    @Test
    @DisplayName("연결 DB와 선택한 테스트 DB 일치")
    void connectedDatabaseMatchesSelection() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:" + testDb + ":");
        }
    }

    @Test
    @DisplayName("MySQL·MariaDB 문자 집합 utf8mb4, 정렬 utf8mb4_bin")
    void mysqlFamilyUsesUtf8mb4Bin() {
        if (testDb.equals("postgresql")) {
            return;
        }
        String charset = jdbcTemplate.queryForObject("SELECT @@character_set_database", String.class);
        String collation = jdbcTemplate.queryForObject("SELECT @@collation_database", String.class);

        assertThat(charset).isEqualTo("utf8mb4");
        assertThat(collation).isEqualTo("utf8mb4_bin");
    }

}
