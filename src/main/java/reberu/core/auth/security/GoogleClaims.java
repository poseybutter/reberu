package reberu.core.auth.security;

import java.util.Locale;
import java.util.Optional;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import reberu.core.account.GoogleProfile;

final class GoogleClaims {

    private static final int SUB_MAX = 255;
    private static final int EMAIL_MAX = 254;
    private static final int NAME_MAX = 255;
    private static final int PICTURE_MAX = 1024;
    private static final int DOMAIN_MAX = 253;

    private GoogleClaims() {
    }

    static Optional<GoogleProfile> profileOf(OidcUser user) {
        String sub = user.getSubject();
        String email = user.getEmail();
        if (!Boolean.TRUE.equals(user.getEmailVerified())
                || sub == null || sub.isBlank() || sub.length() > SUB_MAX
                || email == null || email.isBlank() || email.length() > EMAIL_MAX) {
            return Optional.empty();
        }
        String domain = user.getClaimAsString("hd");
        if (domain != null && (domain.isBlank() || domain.length() > DOMAIN_MAX)) {
            return Optional.empty();
        }
        return Optional.of(new GoogleProfile(
                sub,
                email.toLowerCase(Locale.ROOT),
                truncate(user.getFullName(), NAME_MAX),
                picture(user.getPicture()),
                domain == null ? null : domain.toLowerCase(Locale.ROOT)));
    }

    private static String picture(String url) {
        if (url == null || url.length() > PICTURE_MAX || !url.startsWith("https://")) {
            return null;
        }
        return url;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.codePointCount(0, stripped.length()) <= max) {
            return stripped;
        }
        return stripped.substring(0, stripped.offsetByCodePoints(0, max));
    }

}
