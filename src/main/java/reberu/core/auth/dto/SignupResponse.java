package reberu.core.auth.dto;

import reberu.core.account.GoogleProfile;

public record SignupResponse(String email, String name, String pictureUrl, String termsVersion,
        String privacyVersion) {

    public static SignupResponse from(GoogleProfile profile, String termsVersion, String privacyVersion) {
        return new SignupResponse(profile.email(), profile.name(), profile.pictureUrl(), termsVersion,
                privacyVersion);
    }

}
