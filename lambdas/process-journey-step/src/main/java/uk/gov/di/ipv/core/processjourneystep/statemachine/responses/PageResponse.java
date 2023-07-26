package uk.gov.di.ipv.core.processjourneystep.statemachine.responses;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse implements JourneyStepResponse {

    private String pageId;

    public Map<String, Object> value() {
        return Map.of("page", pageId);
    }
}
