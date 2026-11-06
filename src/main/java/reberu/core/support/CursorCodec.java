package reberu.core.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CursorCodec {

    public static final int MAX_LENGTH = 512;

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 32;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    public CursorCodec(@Value("${reberu.cursor.signing-key}") String signingKey) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(signingKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("커서 서명 키 형식 오류(base64 아님)");
        }
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("커서 서명 키 길이 부족(32바이트 이상 필요)");
        }
        this.key = new SecretKeySpec(bytes, ALGORITHM);
    }

    public String encode(String scope, List<String> position) {
        List<String> parts = new ArrayList<>();
        parts.add(scope);
        parts.addAll(position);
        String payload = String.join(":", parts.stream().map(CursorCodec::encodePart).toList());
        byte[] payloadBytes = payload.getBytes(StandardCharsets.US_ASCII);
        String cursor = ENCODER.encodeToString(payloadBytes) + "." + ENCODER.encodeToString(sign(payloadBytes));
        if (cursor.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("커서 길이 초과");
        }
        return cursor;
    }

    public List<String> decode(String scope, String cursor) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_LENGTH) {
            throw invalid();
        }
        String[] token = cursor.split("\\.", -1);
        if (token.length != 2) {
            throw invalid();
        }
        byte[] payloadBytes = decodeBase64(token[0]);
        if (!MessageDigest.isEqual(sign(payloadBytes), decodeBase64(token[1]))) {
            throw invalid();
        }
        String[] encodedParts = new String(payloadBytes, StandardCharsets.US_ASCII).split(":", -1);
        List<String> parts = new ArrayList<>();
        for (String part : encodedParts) {
            parts.add(new String(decodeBase64(part), StandardCharsets.UTF_8));
        }
        if (!parts.get(0).equals(scope)) {
            throw invalid();
        }
        return List.copyOf(parts.subList(1, parts.size()));
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("커서 서명 실패", e);
        }
    }

    private static String encodePart(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] decodeBase64(String value) {
        try {
            return DECODER.decode(value);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static ReberuException invalid() {
        return new ReberuException(ErrorCode.INVALID_REQUEST);
    }

}
