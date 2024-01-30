package uk.gov.di.ipv.core.revokevcs.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.di.ipv.core.library.domain.UserIdCriIdPair;

import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class RevokeRequest {
    private List<UserIdCriIdPair> userIdCriIdPairs;
}
