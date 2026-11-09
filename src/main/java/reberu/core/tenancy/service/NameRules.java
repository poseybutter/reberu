package reberu.core.tenancy.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import reberu.core.support.FieldReason;
import reberu.core.support.FieldViolation;

public class NameRules {

    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]");
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Cs}\\p{Co}\\p{Cn}]");

    private final List<String> bannedKeys;

    NameRules(List<String> bannedWords) {
        this.bannedKeys = bannedWords.stream()
                .map(NameRules::compact)
                .filter(word -> !word.isEmpty())
                .distinct()
                .toList();
    }

    public static String nameKey(String name) {
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFKC).strip();
        return SPACES.matcher(normalized).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    static String compact(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return NOT_LETTER_OR_DIGIT.matcher(normalized).replaceAll("");
    }

    FieldViolation checkName(String field, String value, int min, int max) {
        if (value == null || value.isBlank()) {
            return new FieldViolation(field, FieldReason.REQUIRED);
        }
        FieldViolation text = checkText(field, value, max);
        if (text != null) {
            return text;
        }
        String stripped = value.strip();
        if (stripped.codePointCount(0, stripped.length()) < min) {
            return new FieldViolation(field, FieldReason.TOO_SHORT);
        }
        if (nameKey(stripped).length() > max) {
            return new FieldViolation(field, FieldReason.TOO_LONG);
        }
        if (containsBanned(stripped)) {
            return new FieldViolation(field, FieldReason.BANNED_WORD);
        }
        return null;
    }

    FieldViolation checkOptionalText(String field, String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        FieldViolation text = checkText(field, value, max);
        if (text != null) {
            return text;
        }
        return containsBanned(value) ? new FieldViolation(field, FieldReason.BANNED_WORD) : null;
    }

    boolean containsBanned(String value) {
        String compacted = compact(value);
        return bannedKeys.stream().anyMatch(compacted::contains);
    }

    private static FieldViolation checkText(String field, String value, int max) {
        if (INVISIBLE.matcher(value).find()) {
            return new FieldViolation(field, FieldReason.INVALID_FORMAT);
        }
        String stripped = value.strip();
        if (stripped.codePointCount(0, stripped.length()) > max) {
            return new FieldViolation(field, FieldReason.TOO_LONG);
        }
        return null;
    }

}
