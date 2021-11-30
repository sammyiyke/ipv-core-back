package uk.gov.di.ipv.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.oauth2.sdk.AccessTokenResponse;
import com.nimbusds.oauth2.sdk.ErrorObject;
import com.nimbusds.oauth2.sdk.TokenErrorResponse;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.oauth2.sdk.token.Tokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.di.ipv.domain.ErrorResponse;
import uk.gov.di.ipv.service.AccessTokenService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccessTokenHandlerTest {
    private final Context context = mock(Context.class);
    private final AccessTokenService accessTokenService = mock(AccessTokenService.class);

    private AccessTokenHandler handler;
    private TokenResponse tokenResponse;

    @BeforeEach
    void setUp() {
        AccessToken accessToken = new BearerAccessToken();
        tokenResponse = new AccessTokenResponse(new Tokens(accessToken, null));
        when(accessTokenService.exchangeCodeForToken(any())).thenReturn(tokenResponse);

        handler = new AccessTokenHandler(accessTokenService);
    }

    @Test
    void shouldReturn200OnSuccessfulAccessTokenExchange() throws Exception {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        String tokenRequestBody = "code=12345&redirect_uri=http://test.com&grant_type=test_grant_type&client_id=test_client_id";
        event.setBody(tokenRequestBody);

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

        assertEquals(200, response.getStatusCode());
    }

    @Test
    void shouldReturnAccessTokenOnSuccessfulExchange() throws Exception {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        String tokenRequestBody = "code=12345&redirect_uri=http://test.com&grant_type=test_grant_type&client_id=test_client_id";
        event.setBody(tokenRequestBody);

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), Map.class);

        assertEquals(tokenResponse.toSuccessResponse().getTokens().getAccessToken().getValue(), responseBody.get("access_token").toString());
    }

    @Test
    void shouldReturn400OnInvalidTokenRequest() throws Exception {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        String invalidTokenRequest = "invalid-token-request";
        event.setBody(invalidTokenRequest);

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

        ObjectMapper objectMapper = new ObjectMapper();
        Map responseBody = objectMapper.readValue(response.getBody(), Map.class);

        assertEquals(400, response.getStatusCode());
        assertEquals(ErrorResponse.FAILED_TO_PARSE_TOKEN_REQUEST.getCode(), responseBody.get("code"));
        assertEquals(ErrorResponse.FAILED_TO_PARSE_TOKEN_REQUEST.getMessage(), responseBody.get("message"));
    }

    @Test
    void shouldReturn400OnFailedTokenExchange() throws Exception {
        ErrorObject tokenErrorObject = new ErrorObject("F-001", "Something failed during exchange of code to token");
        tokenResponse = new TokenErrorResponse(tokenErrorObject);
        when(accessTokenService.exchangeCodeForToken(any())).thenReturn(tokenResponse);

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        String tokenRequestBody = "code=12345&redirect_uri=http://test.com&grant_type=test_grant_type&client_id=test_client_id";

        event.setBody(tokenRequestBody);

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, String> responseBody = objectMapper.readValue(response.getBody(), Map.class);

        assertEquals(400, response.getStatusCode());
        assertEquals(ErrorResponse.FAILED_TO_EXCHANGE_AUTHORIZATION_CODE.getCode(), responseBody.get("code"));
        assertEquals(ErrorResponse.FAILED_TO_EXCHANGE_AUTHORIZATION_CODE.getMessage(), responseBody.get("message"));
    }

    @Test
    void shouldReturn400OnMissingAuthorisationCode() throws Exception {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();

        String tokenRequestBody = "code=&redirect_uri=http://test.com&grant_type=test_grant_type&client_id=test_client_id";

        event.setBody(tokenRequestBody);

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

        ObjectMapper objectMapper = new ObjectMapper();
        Map responseBody = objectMapper.readValue(response.getBody(), Map.class);

        assertEquals(400, response.getStatusCode());
        assertEquals(ErrorResponse.MISSING_AUTHORIZATION_CODE.getCode(), responseBody.get("code"));
        assertEquals(ErrorResponse.MISSING_AUTHORIZATION_CODE.getMessage(), responseBody.get("message"));
    }
}
