package reberu.core.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConsentRequest(
        @NotBlank @Size(max = 20) String termsVersion,
        @NotBlank @Size(max = 20) String privacyVersion
) {
}
