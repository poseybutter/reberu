package reberu.core.auth.security;

import java.util.List;
import java.util.regex.Pattern;

public final class ReturnPaths {

    public static final String DEFAULT = "/start";

    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String KEY = "[a-z0-9][a-z0-9-]{0,39}";
    private static final List<Pattern> ALLOWED = List.of(
            Pattern.compile("/(start|requests|teams|orgs/new|orgs/search|me|me/account|join)"),
            Pattern.compile("/teams/" + UUID),
            Pattern.compile("/teams/" + UUID + "/spaces/" + KEY),
            Pattern.compile("/teams/" + UUID + "/tasks(/" + UUID + ")?"),
            Pattern.compile("/teams/" + UUID + "/admin/" + KEY),
            Pattern.compile("/orgs/" + UUID + "/admin/" + KEY));
    private static final int MAX_LENGTH = 200;

    private ReturnPaths() {
    }

    public static String accept(String next) {
        if (next == null || next.isEmpty() || next.length() > MAX_LENGTH) {
            return null;
        }
        int end = indexOfAny(next, '?', '#');
        String path = end < 0 ? next : next.substring(0, end);
        for (Pattern pattern : ALLOWED) {
            if (pattern.matcher(path).matches()) {
                return path;
            }
        }
        return null;
    }

    private static int indexOfAny(String value, char first, char second) {
        int a = value.indexOf(first);
        int b = value.indexOf(second);
        if (a < 0) {
            return b;
        }
        return b < 0 ? a : Math.min(a, b);
    }

}
