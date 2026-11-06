package reberu.core.support.security;

import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.CacheControlHeadersWriter;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.PermissionsPolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.header.writers.XContentTypeOptionsHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter.XFrameOptionsMode;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data: https://lh3.googleusercontent.com; font-src 'self'; worker-src 'self'; "
            + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; "
            + "object-src 'none'; require-trusted-types-for 'script'; trusted-types reberu-html";
    private static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=()";
    private static final String STRICT_TRANSPORT_SECURITY = "max-age=31536000; includeSubDomains";

    @Bean
    FilterRegistrationBean<HeaderWriterFilter> securityHeadersFilter() {
        HeaderWriterFilter filter = new HeaderWriterFilter(List.of(
                new XContentTypeOptionsHeaderWriter(),
                new XXssProtectionHeaderWriter(),
                new CacheControlHeadersWriter(),
                new StaticHeadersWriter("Strict-Transport-Security", STRICT_TRANSPORT_SECURITY),
                new XFrameOptionsHeaderWriter(XFrameOptionsMode.DENY),
                new ContentSecurityPolicyHeaderWriter(CONTENT_SECURITY_POLICY),
                new ReferrerPolicyHeaderWriter(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN),
                new PermissionsPolicyHeaderWriter(PERMISSIONS_POLICY)));
        filter.setShouldWriteHeadersEagerly(true);
        FilterRegistrationBean<HeaderWriterFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

}
