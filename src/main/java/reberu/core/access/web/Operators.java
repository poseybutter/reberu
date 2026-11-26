package reberu.core.access.web;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class Operators {

    private static final int UUID_LENGTH = 36;

    private final Set<UUID> accountIds;

    private Operators(Set<UUID> accountIds) {
        this.accountIds = Set.copyOf(accountIds);
    }

    static Operators parse(List<String> values) {
        Set<UUID> accountIds = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                accountIds.add(accountId(value.strip()));
            }
        }
        return new Operators(accountIds);
    }

    boolean contains(UUID accountId) {
        return accountId != null && accountIds.contains(accountId);
    }

    private static UUID accountId(String value) {
        if (value.length() != UUID_LENGTH) {
            throw invalid();
        }
        try {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException malformed) {
            throw invalid();
        }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("운영자 계정 ID 형식 오류(OPERATOR_ACCOUNT_IDS는 쉼표로 나눈 계정 UUID 목록)");
    }

}
