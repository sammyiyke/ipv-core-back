package uk.gov.di.ipv.core.endmitigationjourney;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.StringMapMessage;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.tracing.Tracing;
import uk.gov.di.ipv.core.endmitigationjourney.exception.UnknownMitigationJourneyException;
import uk.gov.di.ipv.core.endmitigationjourney.validation.Mj01Validation;
import uk.gov.di.ipv.core.endmitigationjourney.validation.Mj02Validation;
import uk.gov.di.ipv.core.library.annotations.ExcludeFromGeneratedCoverageReport;
import uk.gov.di.ipv.core.library.domain.ContraIndicatorItem;
import uk.gov.di.ipv.core.library.domain.ErrorResponse;
import uk.gov.di.ipv.core.library.domain.JourneyResponse;
import uk.gov.di.ipv.core.library.domain.gpg45.Gpg45ProfileEvaluator;
import uk.gov.di.ipv.core.library.dto.ContraIndicatorMitigationDetailsDto;
import uk.gov.di.ipv.core.library.exceptions.CiPostMitigationsException;
import uk.gov.di.ipv.core.library.exceptions.CiRetrievalException;
import uk.gov.di.ipv.core.library.exceptions.HttpResponseExceptionWithErrorBody;
import uk.gov.di.ipv.core.library.helpers.ApiGatewayResponseGenerator;
import uk.gov.di.ipv.core.library.helpers.LogHelper;
import uk.gov.di.ipv.core.library.helpers.RequestHelper;
import uk.gov.di.ipv.core.library.persistence.item.ClientOAuthSessionItem;
import uk.gov.di.ipv.core.library.persistence.item.IpvSessionItem;
import uk.gov.di.ipv.core.library.service.CiStorageService;
import uk.gov.di.ipv.core.library.service.ClientOAuthSessionDetailsService;
import uk.gov.di.ipv.core.library.service.ConfigService;
import uk.gov.di.ipv.core.library.service.IpvSessionService;
import uk.gov.di.ipv.core.library.service.UserIdentityService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class EndMitigationJourneyHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String MITIGATION_ID = "mitigationId";
    private static final String MJ01 = "MJ01";
    private static final String MJ02 = "MJ02";
    private static final String MITIGATION_JOURNEY_ID = "mitigationJourneyId";
    private static final JourneyResponse JOURNEY_NEXT = new JourneyResponse("/journey/next");

    private static final Logger LOGGER = LogManager.getLogger();

    private UserIdentityService userIdentityService;
    private IpvSessionService ipvSessionService;
    private CiStorageService ciStorageService;
    private ConfigService configService;
    private ClientOAuthSessionDetailsService clientOAuthSessionDetailsService;

    public EndMitigationJourneyHandler(
            UserIdentityService userIdentityService,
            IpvSessionService ipvSessionService,
            CiStorageService ciStorageService,
            ConfigService configService,
            ClientOAuthSessionDetailsService clientOAuthSessionDetailsService) {
        this.userIdentityService = userIdentityService;
        this.ipvSessionService = ipvSessionService;
        this.ciStorageService = ciStorageService;
        this.configService = configService;
        this.clientOAuthSessionDetailsService = clientOAuthSessionDetailsService;
    }

    @ExcludeFromGeneratedCoverageReport
    public EndMitigationJourneyHandler() {
        this.configService = new ConfigService();
        this.userIdentityService = new UserIdentityService(configService);
        this.ipvSessionService = new IpvSessionService(configService);
        this.ciStorageService = new CiStorageService(configService);
        this.clientOAuthSessionDetailsService = new ClientOAuthSessionDetailsService(configService);
    }

    @Override
    @Tracing
    @Logging(clearState = true)
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent input, Context context) {
        LogHelper.attachComponentIdToLogs();

        try {
            String ipvSessionId = RequestHelper.getIpvSessionId(input);
            IpvSessionItem ipvSessionItem = ipvSessionService.getIpvSession(ipvSessionId);

            String ipAddress = RequestHelper.getIpAddress(input);

            String mitigationId = input.getPathParameters().get(MITIGATION_ID);

            ClientOAuthSessionItem clientOAuthSessionItem = null;
            if (ipvSessionItem.getClientOAuthSessionId() != null) {
                clientOAuthSessionItem =
                        clientOAuthSessionDetailsService.getClientOAuthSession(
                                ipvSessionItem.getClientOAuthSessionId());
            }

            final String govUkJourneyId = clientOAuthSessionItem.getGovukSigninJourneyId();
            final String userId = clientOAuthSessionItem.getUserId();

            List<String> credentials = userIdentityService.getUserIssuedCredentials(userId);

            List<ContraIndicatorItem> ciItems =
                    ciStorageService.getCIs(userId, govUkJourneyId, ipAddress);

            ipvSessionItem
                    .getContraIndicatorMitigationDetails()
                    .forEach(
                            contraIndicatorMitigationDetailsDto -> {
                                String ci = contraIndicatorMitigationDetailsDto.getCi();

                                Optional<ContraIndicatorItem> ciItem =
                                        ciItems.stream()
                                                .filter(item -> item.getCi().equals(ci))
                                                .findFirst();
                                try {
                                    if (ciItem.isPresent()) {
                                        Optional<List<String>> mitigatingVcList =
                                                validateMitigationJourney(
                                                        credentials,
                                                        ciItem.get(),
                                                        mitigationId,
                                                        configService);

                                        if (mitigatingVcList.isPresent()) {
                                            StringMapMessage mapMessage =
                                                    new StringMapMessage()
                                                            .with(
                                                                    "lambdaResult",
                                                                    "Mitigating VCs found")
                                                            .with(
                                                                    MITIGATION_JOURNEY_ID,
                                                                    mitigationId);
                                            LOGGER.info(mapMessage);
                                            submitMitigatingVcs(
                                                    mitigatingVcList.get(),
                                                    ipvSessionItem,
                                                    govUkJourneyId,
                                                    ipAddress);
                                        } else {
                                            StringMapMessage mapMessage =
                                                    new StringMapMessage()
                                                            .with(
                                                                    "lambdaResult",
                                                                    "No mitigating VCs were found")
                                                            .with(
                                                                    MITIGATION_JOURNEY_ID,
                                                                    mitigationId);
                                            LOGGER.info(mapMessage);
                                        }
                                    }
                                    endMitigationJourney(
                                            ipvSessionItem, ipvSessionService, mitigationId);
                                } catch (UnknownMitigationJourneyException e) {
                                    var message =
                                            new StringMapMessage()
                                                    .with(
                                                            "message",
                                                            "Unknown mitigation journey ID")
                                                    .with(MITIGATION_JOURNEY_ID, mitigationId);
                                    LOGGER.warn(message);
                                }
                            });
        } catch (HttpResponseExceptionWithErrorBody e) {
            return ApiGatewayResponseGenerator.proxyJsonResponse(
                    e.getResponseCode(), e.getErrorBody());
        } catch (CiRetrievalException e) {
            LOGGER.error("Error when fetching CIs from storage system", e);
            return ApiGatewayResponseGenerator.proxyJsonResponse(
                    HttpStatus.SC_INTERNAL_SERVER_ERROR, ErrorResponse.FAILED_TO_GET_STORED_CIS);
        }

        return ApiGatewayResponseGenerator.proxyJsonResponse(HttpStatus.SC_OK, JOURNEY_NEXT);
    }

    private Optional<List<String>> validateMitigationJourney(
            List<String> credentials,
            ContraIndicatorItem ciItem,
            String mitigationId,
            ConfigService configService)
            throws UnknownMitigationJourneyException {
        if (mitigationId.equals(MJ01)) {
            return Mj01Validation.validateJourney(credentials, ciItem, configService);
        } else if (mitigationId.equals(MJ02)) {
            Gpg45ProfileEvaluator gpg45ProfileEvaluator = new Gpg45ProfileEvaluator(configService);
            return Mj02Validation.validateJourney(credentials, gpg45ProfileEvaluator);
        }
        throw new UnknownMitigationJourneyException(
                String.format("Unknown mitigation journey id: %s", mitigationId));
    }

    private void endMitigationJourney(
            IpvSessionItem ipvSessionItem,
            IpvSessionService ipvSessionService,
            String mitigationJourneyId) {
        List<ContraIndicatorMitigationDetailsDto> contraIndicatorMitigationDetails =
                new ArrayList<>(ipvSessionItem.getContraIndicatorMitigationDetails());

        contraIndicatorMitigationDetails.forEach(
                contraIndicatorMitigationDetailsDto ->
                        contraIndicatorMitigationDetailsDto
                                .getMitigationJourneys()
                                .forEach(
                                        mitigationJourneyDetails -> {
                                            if (mitigationJourneyDetails
                                                    .getMitigationJourneyId()
                                                    .equals(mitigationJourneyId)) {
                                                mitigationJourneyDetails.setComplete(true);
                                            }
                                        }));

        ipvSessionItem.setContraIndicatorMitigationDetails(contraIndicatorMitigationDetails);
        ipvSessionService.updateIpvSession(ipvSessionItem);
    }

    private void submitMitigatingVcs(
            List<String> mitigatingVcs,
            IpvSessionItem ipvSessionItem,
            String govUkJourneyId,
            String ipAddress) {
        try {
            ciStorageService.submitMitigatingVcList(mitigatingVcs, govUkJourneyId, ipAddress);
        } catch (CiPostMitigationsException e) {
            StringMapMessage mapMessage =
                    new StringMapMessage()
                            .with(
                                    "message",
                                    "Failed to send mitigation request to the CI storage system")
                            .with("reason", e.getMessage());
            LOGGER.error(mapMessage);
        }
    }
}
