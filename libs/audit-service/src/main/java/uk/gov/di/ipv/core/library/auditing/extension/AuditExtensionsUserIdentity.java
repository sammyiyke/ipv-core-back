package uk.gov.di.ipv.core.library.auditing.extension;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import uk.gov.di.ipv.core.library.annotations.ExcludeFromGeneratedCoverageReport;

import java.util.List;

@ExcludeFromGeneratedCoverageReport
public record AuditExtensionsUserIdentity(
        @JsonProperty String levelOfConfidence,
        @JsonProperty boolean ciFail,
        @JsonProperty boolean hasMitigations,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty List<String> exitCode)
        implements AuditExtensions {}
