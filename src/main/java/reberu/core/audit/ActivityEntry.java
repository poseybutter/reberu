package reberu.core.audit;

import java.util.UUID;
import java.util.regex.Pattern;
import reberu.core.support.SensitiveText;

public record ActivityEntry(UUID actorId, String action, String targetType, UUID targetId,
        String beforeValue, String afterValue) {

    public static final int MAX_VALUE_LENGTH = 2000;

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    public ActivityEntry {
        requireCode(action, "행동");
        requireCode(targetType, "대상 종류");
        if (targetId == null) {
            throw new IllegalArgumentException("활동 기록 대상 ID 없음");
        }
        requireSafeValue(beforeValue, "이전 값");
        requireSafeValue(afterValue, "이후 값");
    }

    private static void requireCode(String value, String name) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("활동 기록 " + name + " 형식 오류(대문자 상수 40자 이내)");
        }
    }

    private static void requireSafeValue(String value, String name) {
        if (value == null) {
            return;
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("활동 기록 " + name + " 길이 초과(" + MAX_VALUE_LENGTH + "자 이내)");
        }
        if (SensitiveText.containsControl(value)) {
            throw new IllegalArgumentException("활동 기록 " + name + " 거부(제어 문자)");
        }
        if (SensitiveText.containsSensitive(value)) {
            throw new IllegalArgumentException("활동 기록 " + name + " 거부(이메일·주소·토큰 모양 값)");
        }
    }

}
