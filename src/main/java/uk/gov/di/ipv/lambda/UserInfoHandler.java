package uk.gov.di.ipv.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.di.ipv.domain.ErrorResponse;
import uk.gov.di.ipv.dto.UserInfoDto;
import uk.gov.di.ipv.helpers.ApiGatewayResponseGenerator;
import uk.gov.di.ipv.service.UserInfoService;

public class UserInfoHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserInfoHandler.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";

    private UserInfoService userInfoService;

    public UserInfoHandler(UserInfoService userInfoService) {
        this.userInfoService = userInfoService;
    }

    public UserInfoHandler() {
        this.userInfoService = new UserInfoService();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {

        String accessTokenString = input.getHeaders().get(AUTHORIZATION_HEADER);

        if (accessTokenString == null || accessTokenString.isEmpty()) {
            LOGGER.error("Missing required query parameters for authorisation request");
            return ApiGatewayResponseGenerator.proxyErrorResponse(400, ErrorResponse.ERROR_1004);
        }

        try {
            AccessToken accessToken = AccessToken.parse(accessTokenString);
            UserInfoDto userInfo = userInfoService.handleUserInfo(accessToken);

            ObjectMapper objectMapper = new ObjectMapper();
            String userInfoJson = objectMapper.writeValueAsString(userInfo);

            return ApiGatewayResponseGenerator.proxyResponse(200, userInfoJson);

        } catch (ParseException | JsonProcessingException e) {
            LOGGER.error("Failed to parse access token");
            return ApiGatewayResponseGenerator.proxyErrorResponse(400, ErrorResponse.ERROR_1005);
        }
    }
}
