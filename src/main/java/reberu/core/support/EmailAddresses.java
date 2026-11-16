package reberu.core.support;

import java.util.Locale;
import java.util.regex.Pattern;

public final class EmailAddresses {

    public static final int MAX_LENGTH = 254;
    public static final int LOCAL_MAX_LENGTH = 64;

    private static final Pattern LOCAL = Pattern.compile("[a-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*");
    private static final Pattern DOMAIN = Pattern.compile("([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9]([a-z0-9-]*[a-z0-9])?");

    private EmailAddresses() {
    }

    public static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public static boolean valid(String normalized) {
        if (normalized.isEmpty() || normalized.length() > MAX_LENGTH) {
            return false;
        }
        int at = normalized.indexOf('@');
        if (at <= 0 || at > LOCAL_MAX_LENGTH || at != normalized.lastIndexOf('@')) {
            return false;
        }
        return LOCAL.matcher(normalized.substring(0, at)).matches()
                && DOMAIN.matcher(normalized.substring(at + 1)).matches();
    }

}
