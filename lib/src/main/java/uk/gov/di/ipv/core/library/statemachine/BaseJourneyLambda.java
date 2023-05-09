package uk.gov.di.ipv.core.library.statemachine;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpStatus;
import uk.gov.di.ipv.core.library.domain.JourneyResponse;
import uk.gov.di.ipv.core.library.exceptions.HttpResponseExceptionWithErrorBody;
import uk.gov.di.ipv.core.library.helpers.ApiGatewayResponseGenerator;
import uk.gov.di.ipv.core.library.helpers.RequestHelper;

import java.util.Map;

public abstract class BaseJourneyLambda
    implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    public static final JourneyResponse JOURNEY_REUSE = new JourneyResponse("/journey/reuse");
    public static final JourneyResponse JOURNEY_NEXT = new JourneyResponse("/journey/next");
    public static final JourneyResponse JOURNEY_ERROR = new JourneyResponse("/journey/error");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<Map<String, Object>> RETURN_TYPE_REFERENCE =
            new TypeReference<>() {};

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        if (event.containsKey("ipvSessionId")) {
            final var journeyRequest = OBJECT_MAPPER.convertValue(event, JourneyRequest.class);
            final var journeyResponse = handleRequest(journeyRequest, context);

            return OBJECT_MAPPER.convertValue(journeyResponse, RETURN_TYPE_REFERENCE);
        }

        APIGatewayProxyResponseEvent apiGatewayResponse;
        try {
            APIGatewayProxyRequestEvent request = OBJECT_MAPPER.convertValue(event, APIGatewayProxyRequestEvent.class);

            final var ipvSessionId = RequestHelper.getIpvSessionId(request);
            final var ipAddress = RequestHelper.getIpAddress(request);
            final var journeyRequest = new JourneyRequest(ipvSessionId, ipAddress);

            final var journeyResponse = handleRequest(journeyRequest, context);
            apiGatewayResponse = ApiGatewayResponseGenerator.proxyJsonResponse(HttpStatus.SC_OK, journeyResponse);
        } catch (HttpResponseExceptionWithErrorBody e) {
            apiGatewayResponse = ApiGatewayResponseGenerator.proxyJsonResponse(HttpStatus.SC_BAD_REQUEST, JOURNEY_ERROR);
        }

        return OBJECT_MAPPER.convertValue(apiGatewayResponse, RETURN_TYPE_REFERENCE);
    }

    protected abstract JourneyResponse handleRequest(JourneyRequest request, Context context);
}
