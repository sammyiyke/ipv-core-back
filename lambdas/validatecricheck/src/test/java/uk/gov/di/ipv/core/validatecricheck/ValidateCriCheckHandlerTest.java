package uk.gov.di.ipv.core.validatecricheck;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.di.ipv.core.library.domain.JourneyResponse;
import uk.gov.di.ipv.core.library.exceptions.HttpResponseExceptionWithErrorBody;
import uk.gov.di.ipv.core.library.service.UserIdentityService;
import uk.gov.di.ipv.core.validatecricheck.validation.CriCheckValidator;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.di.ipv.core.validatecricheck.ValidateCriCheckHandler.CRI_ID;
import static uk.gov.di.ipv.core.validatecricheck.ValidateCriCheckHandler.IPV_SESSION_ID_HEADER_KEY;
import static uk.gov.di.ipv.core.validatecricheck.ValidateCriCheckHandler.JOURNEY_FAIL;
import static uk.gov.di.ipv.core.validatecricheck.ValidateCriCheckHandler.JOURNEY_NEXT;
import static uk.gov.di.ipv.core.validatecricheck.ValidateCriCheckHandler.OK;

@ExtendWith(MockitoExtension.class)
class ValidateCriCheckHandlerTest {

    private final Gson gson = new Gson();
    private final String criId = "testCriId";
    private final String sessionId = "testSessionId";

    @Mock private Context context;
    @Mock private CriCheckValidator mockCriCheckValidator;
    @Mock private UserIdentityService userIdentityService;
    @InjectMocks private ValidateCriCheckHandler validateCriCheckHandler;

    @Test
    void shouldReturnJourneyNextForSuccessfulCheck() throws HttpResponseExceptionWithErrorBody {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setPathParameters(Map.of(CRI_ID, criId));
        event.setHeaders(Map.of(IPV_SESSION_ID_HEADER_KEY, sessionId));

        when(mockCriCheckValidator.isSuccess(any())).thenReturn(true);

        var response = validateCriCheckHandler.handleRequest(event, context);
        JourneyResponse journeyResponse = gson.fromJson(response.getBody(), JourneyResponse.class);

        assertEquals(OK, response.getStatusCode());
        assertEquals(JOURNEY_NEXT, journeyResponse.getJourney());
        verify(userIdentityService).getUserIssuedCredential(sessionId, criId);
    }

    @Test
    void shouldReturnJourneyFailForUnsuccessfulCheck() throws HttpResponseExceptionWithErrorBody {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setPathParameters(Map.of(CRI_ID, criId));
        event.setHeaders(Map.of(IPV_SESSION_ID_HEADER_KEY, sessionId));

        when(mockCriCheckValidator.isSuccess(any())).thenReturn(false);

        var response = validateCriCheckHandler.handleRequest(event, context);
        JourneyResponse journeyResponse = gson.fromJson(response.getBody(), JourneyResponse.class);

        assertEquals(OK, response.getStatusCode());
        assertEquals(JOURNEY_FAIL, journeyResponse.getJourney());
        verify(userIdentityService).getUserIssuedCredential(sessionId, criId);
    }

    @Test
    void shouldReturnJourneyErrorIfCriIdPathParameterIsNull() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of(IPV_SESSION_ID_HEADER_KEY, sessionId));

        var response = validateCriCheckHandler.handleRequest(event, context);
        var error = gson.fromJson(response.getBody(), Map.class);

        assertEquals(OK, response.getStatusCode());
        assertEquals("/journey/error", error.get("journey"));
    }

    @Test
    void shouldReturnJourneyErrorIfCriIdPathParameterIsEmpty() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setPathParameters(Map.of(CRI_ID, ""));
        event.setHeaders(Map.of(IPV_SESSION_ID_HEADER_KEY, sessionId));

        var response = validateCriCheckHandler.handleRequest(event, context);
        var error = gson.fromJson(response.getBody(), Map.class);

        assertEquals(OK, response.getStatusCode());
        assertEquals("/journey/error", error.get("journey"));
    }

    @Test
    void shouldReturnJourneyErrorIfIpvSessionIdHeaderIsNull() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setPathParameters(Map.of(CRI_ID, criId));

        var response = validateCriCheckHandler.handleRequest(event, context);
        var error = gson.fromJson(response.getBody(), Map.class);

        assertEquals(OK, response.getStatusCode());
        assertEquals("/journey/error", error.get("journey"));
    }

    @Test
    void shouldReturnJourneyErrorIfIpvSessionIdHeaderIsMissing() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setPathParameters(Map.of(CRI_ID, criId));
        event.setHeaders(Map.of(IPV_SESSION_ID_HEADER_KEY, ""));

        var response = validateCriCheckHandler.handleRequest(event, context);
        var error = gson.fromJson(response.getBody(), Map.class);

        assertEquals(OK, response.getStatusCode());
        assertEquals("/journey/error", error.get("journey"));
    }
}
