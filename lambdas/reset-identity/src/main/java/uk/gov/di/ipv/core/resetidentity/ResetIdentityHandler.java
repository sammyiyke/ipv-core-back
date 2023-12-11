package uk.gov.di.ipv.core.resetidentity;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.tracing.Tracing;
import uk.gov.di.ipv.core.library.annotations.ExcludeFromGeneratedCoverageReport;
import uk.gov.di.ipv.core.library.domain.JourneyErrorResponse;
import uk.gov.di.ipv.core.library.domain.JourneyResponse;
import uk.gov.di.ipv.core.library.domain.ProcessRequest;
import uk.gov.di.ipv.core.library.exceptions.HttpResponseExceptionWithErrorBody;
import uk.gov.di.ipv.core.library.helpers.LogHelper;
import uk.gov.di.ipv.core.library.helpers.RequestHelper;
import uk.gov.di.ipv.core.library.persistence.item.ClientOAuthSessionItem;
import uk.gov.di.ipv.core.library.persistence.item.IpvSessionItem;
import uk.gov.di.ipv.core.library.service.ClientOAuthSessionDetailsService;
import uk.gov.di.ipv.core.library.service.ConfigService;
import uk.gov.di.ipv.core.library.service.CriResponseService;
import uk.gov.di.ipv.core.library.service.IpvSessionService;
import uk.gov.di.ipv.core.library.verifiablecredential.service.VerifiableCredentialService;

import java.util.Map;

import static uk.gov.di.ipv.core.library.domain.CriConstants.F2F_CRI;
import static uk.gov.di.ipv.core.library.helpers.RequestHelper.getIpvSessionId;
import static uk.gov.di.ipv.core.library.journeyuris.JourneyUris.JOURNEY_ERROR_PATH;
import static uk.gov.di.ipv.core.library.journeyuris.JourneyUris.JOURNEY_NEXT_PATH;

public class ResetIdentityHandler implements RequestHandler<ProcessRequest, Map<String, Object>> {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Map<String, Object> JOURNEY_NEXT =
            new JourneyResponse(JOURNEY_NEXT_PATH).toObjectMap();
    private final ConfigService configService;
    private final CriResponseService criResponseService;
    private final IpvSessionService ipvSessionService;
    private final ClientOAuthSessionDetailsService clientOAuthSessionDetailsService;
    private final VerifiableCredentialService verifiableCredentialService;

    @SuppressWarnings("unused") // Used by AWS
    public ResetIdentityHandler(
            ConfigService configService,
            IpvSessionService ipvSessionService,
            ClientOAuthSessionDetailsService clientOAuthSessionDetailsService,
            CriResponseService criResponseService,
            VerifiableCredentialService verifiableCredentialService) {
        this.configService = configService;
        this.ipvSessionService = ipvSessionService;
        this.clientOAuthSessionDetailsService = clientOAuthSessionDetailsService;
        this.criResponseService = criResponseService;
        this.verifiableCredentialService = verifiableCredentialService;
    }

    @SuppressWarnings("unused") // Used through dependency injection
    @ExcludeFromGeneratedCoverageReport
    public ResetIdentityHandler() {
        this.configService = new ConfigService();
        this.ipvSessionService = new IpvSessionService(configService);
        this.clientOAuthSessionDetailsService = new ClientOAuthSessionDetailsService(configService);
        this.criResponseService = new CriResponseService(configService);
        this.verifiableCredentialService = new VerifiableCredentialService(configService);
    }

    @Override
    @Tracing
    @Logging(clearState = true)
    public Map<String, Object> handleRequest(ProcessRequest event, Context context) {
        LogHelper.attachComponentIdToLogs(configService);

        try {
            String ipvSessionId = getIpvSessionId(event);
            String featureSet = RequestHelper.getFeatureSet(event);
            Boolean isUserInitiated = RequestHelper.getIsUserInitiated(event);

            configService.setFeatureSet(featureSet);
            IpvSessionItem ipvSessionItem = ipvSessionService.getIpvSession(ipvSessionId);
            ClientOAuthSessionItem clientOAuthSessionItem =
                    clientOAuthSessionDetailsService.getClientOAuthSession(
                            ipvSessionItem.getClientOAuthSessionId());
            String userId = clientOAuthSessionItem.getUserId();
            String govukSigninJourneyId = clientOAuthSessionItem.getGovukSigninJourneyId();
            LogHelper.attachGovukSigninJourneyIdToLogs(govukSigninJourneyId);

            verifiableCredentialService.deleteVcStoreItems(userId, isUserInitiated);
            criResponseService.deleteCriResponseItem(userId, F2F_CRI);

            return JOURNEY_NEXT;
        } catch (HttpResponseExceptionWithErrorBody e) {
            LOGGER.error("HTTP response exception", e);
            return new JourneyErrorResponse(
                            JOURNEY_ERROR_PATH, e.getResponseCode(), e.getErrorResponse())
                    .toObjectMap();
        }
    }
}
