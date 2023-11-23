package uk.gov.di.ipv.core.processjourneyevent.statemachine.stepresponses;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageStepResponse implements StepResponse {

    private String pageId;
    private String context = "";

    public Map<String, Object> value() {
        return Map.of(
                "page", pageId,
                "context", context
        );
    }
}
