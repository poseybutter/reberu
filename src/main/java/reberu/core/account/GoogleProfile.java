package reberu.core.account;

import java.util.Objects;

public record GoogleProfile(String sub, String email, String name, String pictureUrl, String workspaceDomain) {

    public GoogleProfile {
        Objects.requireNonNull(sub);
        Objects.requireNonNull(email);
    }

}
