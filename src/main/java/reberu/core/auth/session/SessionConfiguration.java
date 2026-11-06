package reberu.core.auth.session;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableSpringHttpSession
@EnableScheduling
@EnableConfigurationProperties(SessionProperties.class)
class SessionConfiguration {

    static final String COOKIE_NAME = "__Host-session";

    @Bean
    JdbcAuthSessionRepository authSessionRepository(JdbcClient jdbc, TransactionTemplate transactions,
            SessionProperties properties) {
        return new JdbcAuthSessionRepository(jdbc, transactions, properties, Clock.systemUTC());
    }

    @Bean
    CookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(COOKIE_NAME);
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(true);
        serializer.setSameSite("Lax");
        serializer.setUseBase64Encoding(false);
        return serializer;
    }

    @Bean
    AuthSessionCleanup authSessionCleanup(JdbcAuthSessionRepository repository) {
        return new AuthSessionCleanup(repository);
    }

}
