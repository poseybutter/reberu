package reberu.core.account;

public record LockedAccount(AccountState state, String email, boolean emailShared) {
}
