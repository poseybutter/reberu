package reberu.core.support;

import java.util.Locale;

public final class EmailAddresses {

    public static final int MAX_LENGTH = 254;
    public static final int LOCAL_MAX_LENGTH = 64;

    private EmailAddresses() {
    }

    public static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public static boolean valid(String normalized) {
        if (normalized.isEmpty() || normalized.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c <= 0x20 || c >= 0x7F) {
                return false;
            }
        }
        int at = normalized.indexOf('@');
        if (at <= 0 || at > LOCAL_MAX_LENGTH || at != normalized.lastIndexOf('@')) {
            return false;
        }
        String domain = normalized.substring(at + 1);
        int dot = domain.indexOf('.');
        return dot > 0 && !domain.endsWith(".") && !domain.contains("..");
    }

}
