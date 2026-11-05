package reberu;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final String DB_PROPERTY = "reberu.test.db";

    private static final String[] MYSQL_FAMILY_COMMAND = {
        "--character-set-server=utf8mb4",
        "--collation-server=utf8mb4_bin"
    };

    @Bean
    @ServiceConnection
    @ConditionalOnProperty(name = DB_PROPERTY, havingValue = "postgresql", matchIfMissing = true)
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
    }

    @Bean
    @ServiceConnection
    @ConditionalOnProperty(name = DB_PROPERTY, havingValue = "mysql")
    MySQLContainer mysqlContainer() {
        return new MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withCommand(MYSQL_FAMILY_COMMAND);
    }

    @Bean
    @ServiceConnection
    @ConditionalOnProperty(name = DB_PROPERTY, havingValue = "mariadb")
    MariaDBContainer mariadbContainer() {
        return new MariaDBContainer(DockerImageName.parse("mariadb:11.4"))
                .withCommand(MYSQL_FAMILY_COMMAND);
    }

}
