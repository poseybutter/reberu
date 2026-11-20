package reberu.core.tenancy.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public final class GuestRequests {

    private GuestRequests() {
    }

    public record Extend(@NotNull UUID teamId, @NotNull Integer days) {
    }

}
