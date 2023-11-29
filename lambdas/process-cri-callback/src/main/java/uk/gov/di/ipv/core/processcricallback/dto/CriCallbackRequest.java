package uk.gov.di.ipv.core.processcricallback.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.di.ipv.core.library.annotations.ExcludeFromGeneratedCoverageReport;

@ExcludeFromGeneratedCoverageReport
@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class CriCallbackRequest {
    private String authorizationCode;
    private String credentialIssuerId;
    private String ipvSessionId;
    private String redirectUri;
    private String state;
    private String error;
    private String errorDescription;
    private String ipAddress;
    private String featureSet;
}
