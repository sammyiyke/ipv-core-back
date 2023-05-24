package uk.gov.di.ipv.core.library.statemachine;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpStatus;
import uk.gov.di.ipv.core.library.domain.BaseResponse;
import uk.gov.di.ipv.core.library.domain.JourneyErrorResponse;
import uk.gov.di.ipv.core.library.domain.JourneyRequest;
import uk.gov.di.ipv.core.library.domain.JourneyResponse;
import uk.gov.di.ipv.core.library.exceptions.HttpResponseExceptionWithErrorBody;
import uk.gov.di.ipv.core.library.helpers.ApiGatewayResponseGenerator;
import uk.gov.di.ipv.core.library.helpers.LogHelper;
import uk.gov.di.ipv.core.library.helpers.RequestHelper;

import java.util.Map;

import static uk.gov.di.ipv.core.library.helpers.RequestHelper.getClientOAuthSessionId;
import static uk.gov.di.ipv.core.library.helpers.RequestHelper.getFeatureSet;

public abstract class BaseJourneyLambda
        implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    public static final String JOURNEY_ERROR_PATH = "/journey/error";
    public static final String JOURNEY_NEXT_PATH = "/journey/next";
    public static final JourneyResponse JOURNEY_REUSE = new JourneyResponse("/journey/reuse");
    public static final JourneyResponse JOURNEY_NEXT = new JourneyResponse(JOURNEY_NEXT_PATH);
    public static final JourneyResponse JOURNEY_ERROR = new JourneyResponse(JOURNEY_ERROR_PATH);
    private static final String CRI_ID = "criId";

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<Map<String, Object>> RETURN_TYPE_REFERENCE =
            new TypeReference<>() {};

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        if (event.containsKey("ipvSessionId")) {
            var journeyRequest = OBJECT_MAPPER.convertValue(event, JourneyRequest.class);
            var journeyResponse = handleRequest(journeyRequest, context);

            return OBJECT_MAPPER.convertValue(journeyResponse, RETURN_TYPE_REFERENCE);
        }

        APIGatewayProxyResponseEvent apiGatewayResponse;
        try {
            APIGatewayProxyRequestEvent request =
                    OBJECT_MAPPER.convertValue(event, APIGatewayProxyRequestEvent.class);

            var journeyRequest =
                    JourneyRequest.builder()
                            .ipvSessionId(getIpvSessionId(request))
                            .ipAddress(getIpAddress(request))
                            .journey(RequestHelper.getJourney(request, CRI_ID))
                            .clientOAuthSessionId(getClientOAuthSessionId(request))
                            .featureSet(getFeatureSet(request))
                            .build();
            var journeyResponse = handleRequest(journeyRequest, context);

            apiGatewayResponse =
                    ApiGatewayResponseGenerator.proxyJsonResponse(
                            HttpStatus.SC_OK, journeyResponse);
        } catch (Exception ex) {
            LogHelper.logErrorMessage("Error during lambda processing.", ex.getMessage());
            var journeyResponse =
                    new JourneyErrorResponse(
                            JOURNEY_ERROR_PATH, HttpStatus.SC_INTERNAL_SERVER_ERROR, null);

            apiGatewayResponse =
                    ApiGatewayResponseGenerator.proxyJsonResponse(
                            HttpStatus.SC_BAD_REQUEST, journeyResponse);
        }

        return OBJECT_MAPPER.convertValue(apiGatewayResponse, RETURN_TYPE_REFERENCE);
    }

    private static String getIpvSessionId(APIGatewayProxyRequestEvent request) {
        String ipvSessionId;
        try {
            ipvSessionId = RequestHelper.getIpvSessionId(request);
        } catch (HttpResponseExceptionWithErrorBody e) {
            ipvSessionId = null;
        }
        return ipvSessionId;
    }

    private static String getIpAddress(APIGatewayProxyRequestEvent request) {
        String ipAddress;
        try {
            ipAddress = RequestHelper.getIpAddress(request);
        } catch (HttpResponseExceptionWithErrorBody e) {
            ipAddress = null;
        }
        return ipAddress;
    }

    protected abstract BaseResponse handleRequest(JourneyRequest request, Context context);
}
