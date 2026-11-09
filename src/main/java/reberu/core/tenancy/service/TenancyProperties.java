package reberu.core.tenancy.service;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import reberu.core.tenancy.DeploymentMode;

@ConfigurationProperties("reberu.tenancy")
public record TenancyProperties(DeploymentMode mode, SingleOrg singleOrg, List<String> bannedWords) {

    public TenancyProperties {
        mode = mode == null ? DeploymentMode.MULTI_ORG : mode;
        bannedWords = bannedWords == null ? List.of() : List.copyOf(bannedWords);
        if (mode == DeploymentMode.SINGLE_ORG) {
            if (singleOrg == null || isBlank(singleOrg.name()) || isBlank(singleOrg.adminEmail())
                    || !validTimeZone(singleOrg.timeZone())) {
                throw new IllegalStateException(
                        "SINGLE_ORG 설정 값 오류(SINGLE_ORG_NAME, SINGLE_ORG_TIME_ZONE, SINGLE_ORG_ADMIN_EMAIL 필요)");
            }
        }
    }

    public boolean singleOrgMode() {
        return mode == DeploymentMode.SINGLE_ORG;
    }

    static boolean validTimeZone(String value) {
        return value != null && ZoneId.getAvailableZoneIds().contains(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record SingleOrg(String name, String timeZone, String adminEmail) {

        public SingleOrg {
            name = name == null ? null : name.strip();
            adminEmail = adminEmail == null ? null : adminEmail.strip().toLowerCase(Locale.ROOT);
        }

    }

}
