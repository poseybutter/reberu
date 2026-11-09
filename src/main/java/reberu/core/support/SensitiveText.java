package reberu.core.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SensitiveText {

    private static final Pattern EMAIL =
            Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)");
    private static final Pattern URL_QUERY = Pattern.compile("([A-Za-z][A-Za-z0-9+.-]*://[^\\s?#]*)[?#]\\S*");
    private static final Pattern URL = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");
    private static final Pattern TOKEN_RUN = Pattern.compile("[A-Za-z0-9+_-]{32,}={0,2}");
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private static final String HIDDEN = "***";

    private SensitiveText() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = EMAIL.matcher(text).replaceAll("$1" + HIDDEN + "@$2");
        masked = URL_QUERY.matcher(masked).replaceAll("$1?" + HIDDEN);
        masked = JWT.matcher(masked).replaceAll(HIDDEN);
        return maskTokens(masked);
    }

    public static String escapeLineBreaks(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }

    public static boolean containsSensitive(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return EMAIL.matcher(text).find() || URL.matcher(text).find() || JWT.matcher(text).find() || hasToken(text);
    }

    public static boolean containsControl(String text) {
        return text != null && CONTROL.matcher(text).find();
    }

    private static String maskTokens(String text) {
        Matcher matcher = TOKEN_RUN.matcher(text);
        StringBuilder result = new StringBuilder(text.length());
        while (matcher.find()) {
            String run = matcher.group();
            matcher.appendReplacement(result, Matcher.quoteReplacement(isToken(run) ? HIDDEN : run));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static boolean hasToken(String text) {
        Matcher matcher = TOKEN_RUN.matcher(text);
        while (matcher.find()) {
            if (isToken(matcher.group())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isToken(String run) {
        if (UUID_TEXT.matcher(run).matches()) {
            return false;
        }
        return run.chars().anyMatch(Character::isDigit) && run.chars().anyMatch(Character::isLetter);
    }

}
