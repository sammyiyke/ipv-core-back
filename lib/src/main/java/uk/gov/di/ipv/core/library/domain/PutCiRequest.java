package uk.gov.di.ipv.core.library.domain;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class PutCiRequest {
    @SerializedName(value = "govuk_signin_journey_id")
    private final String govukSigninJourneyId;

    @SerializedName(value = "ip_address")
    private final String ipAddress;

    @SerializedName(value = "signed_jwt")
    private final String signedJwt;
}
