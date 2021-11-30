package uk.gov.di.ipv.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.di.ipv.domain.ErrorResponse;
import uk.gov.di.ipv.service.AuthorizationCodeService;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AuthorizationHandlerTest {
    private final Context context = mock(Context.class);
    private AuthorizationCodeService mockAuthorizationCodeService;

    private AuthorizationHandler handler;
    private AuthorizationCode authorizationCode;

    @BeforeEach
    public void setUp() {
        mockAuthorizationCodeService = mock(AuthorizationCodeService.class);

        authorizationCode = new AuthorizationCode();
        when(mockAuthorizationCodeService.generateAuthorizationCode()).thenReturn(authorizationCode);

        handler = new AuthorizationHandler(mockAuthorizationCodeService);
    }

    @Test
    public void shouldReturn200OnSuccessfulOauthRequest(){
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        Map<String, String> params = new HashMap<>();
        params.put("redirect_uri", "http://example.com");
        params.put("client_id", "12345");
        params.put("response_type", "code");
        params.put("scope", "openid");
        event.setQueryStringParameters(params);

        event.setHeaders(Map.of("ipv-session-id", "12345"));

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

        assertEquals(200, response.getStatusCode());
    }

    @Test
    public void shouldReturnAuthResponseOnSuccessfulOauthRequest() throws Exception {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        Map<String, String> params = new HashMap<>();
        params.put("redirect_uri", "http://example.com");
        params.put("client_id", "12345");
        params.put("response_type", "code");
        params.put("scope", "openid");
        event.setQueryStringParameters(params);

        event.setHeaders(Map.of("ipv-session-id", "12345"));

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), Map.class);
        Map<String, String> authCode = (Map) responseBody.get("code");

        verify(mockAuthorizationCodeService).persistAuthorizationCode(authCode.get("value"), "12345");
        assertEquals(authorizationCode.toString(), authCode.get("value"));
    }

    @Test
    public void shouldReturn400OnMissingRedirectUriParam() throws Exception {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        Map<String, String> params = new HashMap<>();
        params.put("client_id", "12345");
        params.put("response_type", "code");
        params.put("scope", "openid");
        event.setQueryStringParameters(params);

        event.setHeaders(Map.of("ipv-session-id", "12345"));

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

        ObjectMapper objectMapper = new ObjectMapper();
        Map responseBody = objectMapper.readValue(response.getBody(), Map.class);

        assertEquals(400, response.getStatusCode());
        assertEquals(ErrorResponse.FailedToParseOauthQueryStringParameters.getCode(), responseBody.get("code"));
        assertEquals(ErrorResponse.FailedToParseOauthQueryStringParameters.getMessage(), responseBody.get("message"));
    }

    @Test
    public void shouldReturn400OnMissingClientIdParam() throws Exception {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        Map<String, String> params = new HashMap<>();
        params.put("redirect_uri", "http://example.com");
        params.put("response_type", "code");
        params.put("scope", "openid");
        event.setQueryStringParameters(params);

        event.setHeaders(Map.of("ipv-session-id", "12345"));

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

        ObjectMapper objectMapper = new ObjectMapper();
        Map responseBody = objectMapper.readValue(response.getBody(), Map.class);

        assertEquals(400, response.getStatusCode());
        assertEquals(ErrorResponse.FailedToParseOauthQueryStringParameters.getCode(), responseBody.get("code"));
        assertEquals(ErrorResponse.FailedToParseOauthQueryStringParameters.getMessage(), responseBody.get("message"));
    }

    @Test
    public void shouldReturn400OnMissingResponseTypeParam() throws Exception {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        Map<String, String> params = new HashMap<>();
        params.put("redirect_uri", "http://example.com");
        params.put("client_id", "12345");
        params.put("scope", "openid");
        event.setQueryStringParameters(params);

        event.setHeaders(Map.of("ipv-session-id", "12345"));

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

        ObjectMapper objectMapper = new ObjectMapper();
        Map responseBody = objectMapper.readValue(response.getBody(), Map.class);

        assertEquals(400, response.getStatusCode());
        assertEquals(ErrorResponse.FailedToParseOauthQueryStringParameters.getCode(), responseBody.get("code"));
        assertEquals(ErrorResponse.FailedToParseOauthQueryStringParameters.getMessage(), responseBody.get("message"));
    }

    @Test
    public void shouldReturn400OnMissingScopeParam() throws Exception {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        Map<String, String> params = new HashMap<>();
        params.put("redirect_uri", "http://example.com");
        params.put("client_id", "12345");
        params.put("response_type", "code");
        event.setQueryStringParameters(params);

        event.setHeaders(Map.of("ipv-session-id", "12345"));

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

        ObjectMapper objectMapper = new ObjectMapper();
        Map responseBody = objectMapper.readValue(response.getBody(), Map.class);

        assertEquals(400, response.getStatusCode());
        assertEquals(ErrorResponse.FailedToParseOauthQueryStringParameters.getCode(), responseBody.get("code"));
        assertEquals(ErrorResponse.FailedToParseOauthQueryStringParameters.getMessage(), responseBody.get("message"));
    }

    @Test
    public void shouldReturn400OnMissingQueryParameters() throws Exception {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();

        event.setHeaders(Map.of("ipv-session-id", "12345"));

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

        ObjectMapper objectMapper = new ObjectMapper();
        Map responseBody = objectMapper.readValue(response.getBody(), Map.class);

        assertEquals(400, response.getStatusCode());
        assertEquals(ErrorResponse.MissingQueryParameters.getCode(), responseBody.get("code"));
        assertEquals(ErrorResponse.MissingQueryParameters.getMessage(), responseBody.get("message"));
    }

    @Test
    public void shouldReturn400OnMissingSessionIdHeader() throws Exception {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        Map<String, String> params = new HashMap<>();
        params.put("redirect_uri", "http://example.com");
        params.put("client_id", "12345");
        params.put("response_type", "code");
        params.put("scope", "openid");
        event.setQueryStringParameters(params);

        event.setHeaders(Collections.emptyMap());

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

        ObjectMapper objectMapper = new ObjectMapper();
        Map responseBody = objectMapper.readValue(response.getBody(), Map.class);

        assertEquals(400, response.getStatusCode());
        assertEquals(ErrorResponse.MissingIpvSessionId.getCode(), responseBody.get("code"));
        assertEquals(ErrorResponse.MissingIpvSessionId.getMessage(), responseBody.get("message"));
    }
}
