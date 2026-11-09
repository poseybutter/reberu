package reberu;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String DB_PROPERTY = "reberu.test.db";

    private static final String[] MYSQL_FAMILY_COMMAND = {
        "--character-set-server=utf8mb4",
        "--collation-server=utf8mb4_bin",
        "--log-bin-trust-function-creators=1"
    };

    @Bean
    @ServiceConnection
    @ConditionalOnProperty(name = DB_PROPERTY, havingValue = "postgresql", matchIfMissing = true)
    PostgreSQLContainer postgresContainer() {
        return newPostgresContainer();
    }

    @Bean
    @ServiceConnection
    @ConditionalOnProperty(name = DB_PROPERTY, havingValue = "mysql")
    MySQLContainer mysqlContainer() {
        return newMysqlContainer();
    }

    @Bean
    @ServiceConnection
    @ConditionalOnProperty(name = DB_PROPERTY, havingValue = "mariadb")
    MariaDBContainer mariadbContainer() {
        return newMariadbContainer();
    }

    public static JdbcDatabaseContainer<?> newSelectedContainer() {
        String db = System.getProperty(DB_PROPERTY, "postgresql");
        return switch (db) {
            case "postgresql" -> newPostgresContainer();
            case "mysql" -> newMysqlContainer();
            case "mariadb" -> newMariadbContainer();
            default -> throw new IllegalArgumentException("지원하지 않는 테스트 DB: " + db);
        };
    }

    private static PostgreSQLContainer newPostgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
    }

    private static MySQLContainer newMysqlContainer() {
        return new MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withCommand(MYSQL_FAMILY_COMMAND);
    }

    private static MariaDBContainer newMariadbContainer() {
        return new MariaDBContainer(DockerImageName.parse("mariadb:11.4"))
                .withCommand(MYSQL_FAMILY_COMMAND);
    }

}
