package uk.gov.di.ipv.core.library.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import uk.gov.di.ipv.core.library.annotations.ExcludeFromGeneratedCoverageReport;

import java.util.Optional;

@ExcludeFromGeneratedCoverageReport
public class CredentialIssuerRequestDto {
    private String authorizationCode;
    private String credentialIssuerId;
    private String ipvSessionId;
    private String redirectUri;
    private String state;
    private String error;
    private String errorDescription;

    @JsonCreator
    public CredentialIssuerRequestDto(
            @JsonProperty(value = "authorization_code") String authorizationCode,
            @JsonProperty(value = "credential_issuer_id") String credentialIssuerId,
            @JsonProperty(value = "ipv_session_id") String ipvSessionId,
            @JsonProperty(value = "redirect_uri") String redirectUri,
            @JsonProperty(value = "state") String state,
            @JsonProperty(value = "error") String error,
            @JsonProperty(value = "error_description") String errorDescription) {
        this.authorizationCode = authorizationCode;
        this.credentialIssuerId = credentialIssuerId;
        this.ipvSessionId = ipvSessionId;
        this.redirectUri = redirectUri;
        this.state = state;
        this.error = error;
        this.errorDescription = errorDescription;
    }

    public String getAuthorizationCode() {
        return authorizationCode;
    }

    public void setAuthorizationCode(String authorizationCode) {
        this.authorizationCode = authorizationCode;
    }

    public String getCredentialIssuerId() {
        return credentialIssuerId;
    }

    public void setCredentialIssuerId(String credentialIssuerId) {
        this.credentialIssuerId = credentialIssuerId;
    }

    public String getIpvSessionId() {
        return ipvSessionId;
    }

    public void setIpvSessionId(String ipvSessionId) {
        this.ipvSessionId = ipvSessionId;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Optional<String> getError() {
        return Optional.ofNullable(error);
    }

    public void setError(String error) {
        this.error = error;
    }

    public Optional<String> getErrorDescription() {
        return Optional.ofNullable(errorDescription);
    }

    public void setErrorDescription(String errorDescription) {
        this.errorDescription = errorDescription;
    }
}
