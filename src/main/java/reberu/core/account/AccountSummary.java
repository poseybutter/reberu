package reberu.core.account;

import java.util.UUID;

public record AccountSummary(UUID id, AccountState state) {
}
