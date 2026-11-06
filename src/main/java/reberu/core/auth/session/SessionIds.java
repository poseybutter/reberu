package reberu.core.auth.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class SessionIds {

    private static final int ID_BYTES = 32;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private SessionIds() {
    }

    static String newId() {
        byte[] bytes = new byte[ID_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static boolean isId(String value) {
        return value != null && ID.matcher(value).matches();
    }

    static boolean isHash(String value) {
        return HASH.matcher(value).matches();
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

}
