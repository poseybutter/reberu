package reberu.core.auth.session;

import java.time.Instant;
import java.util.Objects;

public record LoginProgress(String stateHash, String nonce, String codeVerifier, String returnPath, Instant startedAt) {

    public LoginProgress {
        Objects.requireNonNull(startedAt);
        if (stateHash == null || !SessionIds.isHash(stateHash)) {
            throw new IllegalArgumentException("로그인 state 해시 형식 오류");
        }
        if (nonce == null || nonce.isEmpty() || nonce.length() > 64) {
            throw new IllegalArgumentException("로그인 nonce 길이 오류");
        }
        if (codeVerifier == null || codeVerifier.length() < 43 || codeVerifier.length() > 128) {
            throw new IllegalArgumentException("로그인 code_verifier 길이 오류");
        }
        if (returnPath != null && (returnPath.length() > 200
                || !returnPath.startsWith("/") || returnPath.startsWith("//") || returnPath.contains("\\"))) {
            throw new IllegalArgumentException("로그인 뒤 돌아갈 경로 오류(앱 안 상대 경로만)");
        }
    }

}
