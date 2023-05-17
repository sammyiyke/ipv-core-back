package uk.gov.di.ipv.core.getcredentialissuerconfig;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.tracing.Tracing;
import uk.gov.di.ipv.core.library.credentialissuer.CredentialIssuerConfigService;
import uk.gov.di.ipv.core.library.domain.ErrorResponse;
import uk.gov.di.ipv.core.library.dto.CredentialIssuerConfig;
import uk.gov.di.ipv.core.library.exceptions.ParseCredentialIssuerConfigException;
import uk.gov.di.ipv.core.library.helpers.ApiGatewayResponseGenerator;
import uk.gov.di.ipv.core.library.helpers.LogHelper;
import uk.gov.di.ipv.core.library.helpers.RequestHelper;

import java.util.List;

public class GetCredentialIssuerConfigHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final CredentialIssuerConfigService credentialIssuerConfigService;
    private static final Logger LOGGER = LogManager.getLogger();

    public GetCredentialIssuerConfigHandler(
            CredentialIssuerConfigService credentialIssuerConfigService) {
        this.credentialIssuerConfigService = credentialIssuerConfigService;
    }

    public GetCredentialIssuerConfigHandler() {
        credentialIssuerConfigService = new CredentialIssuerConfigService();
    }

    @Override
    @Tracing
    @Logging(clearState = true)
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent input, Context context) {
        LogHelper.attachComponentIdToLogs();
        try {
            String featureSet = RequestHelper.getFeatureSet(input);
            credentialIssuerConfigService.setFeatureSet(featureSet);
            List<CredentialIssuerConfig> config =
                    credentialIssuerConfigService.getCredentialIssuers();
            return ApiGatewayResponseGenerator.proxyJsonResponse(200, config);
        } catch (ParseCredentialIssuerConfigException e) {
            LogHelper.logErrorMessage("Failed to load credential issuer config.", e.getMessage());
            return ApiGatewayResponseGenerator.proxyJsonResponse(
                    500, ErrorResponse.FAILED_TO_PARSE_CREDENTIAL_ISSUER_CONFIG);
        }
    }
}
