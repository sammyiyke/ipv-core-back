package uk.gov.di.ipv.core.processcricallback;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.tracing.Tracing;
import uk.gov.di.ipv.core.library.annotations.ExcludeFromGeneratedCoverageReport;
import uk.gov.di.ipv.core.library.cimit.exception.CiPostMitigationsException;
import uk.gov.di.ipv.core.library.cimit.exception.CiPutException;
import uk.gov.di.ipv.core.library.cimit.exception.CiRetrievalException;
import uk.gov.di.ipv.core.library.domain.ErrorResponse;
import uk.gov.di.ipv.core.library.domain.JourneyErrorResponse;
import uk.gov.di.ipv.core.library.domain.JourneyResponse;
import uk.gov.di.ipv.core.library.exceptions.ConfigException;
import uk.gov.di.ipv.core.library.exceptions.CredentialParseException;
import uk.gov.di.ipv.core.library.exceptions.HttpResponseExceptionWithErrorBody;
import uk.gov.di.ipv.core.library.exceptions.SqsException;
import uk.gov.di.ipv.core.library.exceptions.VerifiableCredentialException;
import uk.gov.di.ipv.core.library.helpers.ApiGatewayResponseGenerator;
import uk.gov.di.ipv.core.library.helpers.LogHelper;
import uk.gov.di.ipv.core.library.helpers.StepFunctionHelpers;
import uk.gov.di.ipv.core.library.kmses256signer.KmsEs256Signer;
import uk.gov.di.ipv.core.library.persistence.item.CriOAuthSessionItem;
import uk.gov.di.ipv.core.library.persistence.item.IpvSessionItem;
import uk.gov.di.ipv.core.library.service.AuditService;
import uk.gov.di.ipv.core.library.service.CiMitService;
import uk.gov.di.ipv.core.library.service.ClientOAuthSessionDetailsService;
import uk.gov.di.ipv.core.library.service.ConfigService;
import uk.gov.di.ipv.core.library.service.CriOAuthSessionService;
import uk.gov.di.ipv.core.library.service.CriResponseService;
import uk.gov.di.ipv.core.library.service.IpvSessionService;
import uk.gov.di.ipv.core.library.service.UserIdentityService;
import uk.gov.di.ipv.core.library.verifiablecredential.exception.VerifiableCredentialResponseException;
import uk.gov.di.ipv.core.library.verifiablecredential.helpers.VcHelper;
import uk.gov.di.ipv.core.library.verifiablecredential.service.VerifiableCredentialService;
import uk.gov.di.ipv.core.library.verifiablecredential.validator.VerifiableCredentialJwtValidator;
import uk.gov.di.ipv.core.processcricallback.dto.CriCallbackRequest;
import uk.gov.di.ipv.core.processcricallback.exception.CriApiException;
import uk.gov.di.ipv.core.processcricallback.exception.InvalidCriCallbackRequestException;
import uk.gov.di.ipv.core.processcricallback.exception.ParseCriCallbackRequestException;
import uk.gov.di.ipv.core.processcricallback.service.CriApiService;
import uk.gov.di.ipv.core.processcricallback.service.CriCheckingService;
import uk.gov.di.ipv.core.processcricallback.service.CriStoringService;

import java.text.ParseException;

import static uk.gov.di.ipv.core.library.journeyuris.JourneyUris.JOURNEY_ERROR_PATH;

public class ProcessCriCallbackHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String PYI_ATTEMPT_RECOVERY_PAGE_ID = "pyi-attempt-recovery";
    private final ConfigService configService;
    private final CriApiService criApiService;
    private final CriStoringService criStoringService;
    private final CriCheckingService criCheckingService;
    private final IpvSessionService ipvSessionService;
    private final CriOAuthSessionService criOAuthSessionService;
    private final ClientOAuthSessionDetailsService clientOAuthSessionDetailsService;

    public ProcessCriCallbackHandler(
            ConfigService configService,
            IpvSessionService ipvSessionService,
            CriOAuthSessionService criOAuthSessionService,
            ClientOAuthSessionDetailsService clientOAuthSessionDetailsService,
            CriApiService vcFetchingService,
            CriStoringService criStoringService,
            CriCheckingService criCheckingService) {
        this.configService = configService;
        this.criApiService = vcFetchingService;
        this.criStoringService = criStoringService;
        this.criCheckingService = criCheckingService;
        this.ipvSessionService = ipvSessionService;
        this.criOAuthSessionService = criOAuthSessionService;
        this.clientOAuthSessionDetailsService = clientOAuthSessionDetailsService;
        VcHelper.setConfigService(this.configService);
    }

    @ExcludeFromGeneratedCoverageReport
    public ProcessCriCallbackHandler() {
        configService = new ConfigService();
        ipvSessionService = new IpvSessionService(configService);
        criOAuthSessionService = new CriOAuthSessionService(configService);
        clientOAuthSessionDetailsService = new ClientOAuthSessionDetailsService(configService);

        var userIdentityService = new UserIdentityService(configService);
        var auditService = new AuditService(AuditService.getDefaultSqsClient(), configService);
        var verifiableCredentialService = new VerifiableCredentialService(configService);
        var verifiableCredentialJwtValidator = new VerifiableCredentialJwtValidator(configService);
        var ciMitService = new CiMitService(configService);
        var criResponseService = new CriResponseService(configService);
        var signer = new KmsEs256Signer();

        signer.setKeyId(configService.getSigningKeyId());
        VcHelper.setConfigService(configService);

        criApiService = new CriApiService(configService, signer, criOAuthSessionService);
        criCheckingService =
                new CriCheckingService(
                        configService,
                        auditService,
                        userIdentityService,
                        ciMitService,
                        verifiableCredentialJwtValidator);
        criStoringService =
                new CriStoringService(
                        configService,
                        auditService,
                        criResponseService,
                        verifiableCredentialService,
                        ciMitService);
    }

    @Override
    @Tracing
    @Logging(clearState = true)
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent input, Context context) {
        try {
            var callbackRequest = parseCallbackRequest(input);
            criCheckingService.validateCallbackRequest(callbackRequest);

            var journeyResponse = getJourneyResponse(callbackRequest);

            return ApiGatewayResponseGenerator.proxyJsonResponse(HttpStatus.SC_OK, journeyResponse);
        } catch (ParseCriCallbackRequestException e) {
            return buildErrorResponse(
                    e,
                    HttpStatus.SC_BAD_REQUEST,
                    ErrorResponse.FAILED_TO_PARSE_CRI_CALLBACK_REQUEST);
        } catch (InvalidCriCallbackRequestException e) {
            return buildErrorResponse(e, HttpStatus.SC_BAD_REQUEST, e.getErrorResponse());
        } catch (HttpResponseExceptionWithErrorBody e) {
            if (e.getErrorResponse() == ErrorResponse.INVALID_OAUTH_STATE) {
                LOGGER.error("Error in process cri callback lambda", e);
                return ApiGatewayResponseGenerator.proxyJsonResponse(
                        HttpStatus.SC_BAD_REQUEST,
                        StepFunctionHelpers.generatePageOutputMap(
                                "error", HttpStatus.SC_BAD_REQUEST, PYI_ATTEMPT_RECOVERY_PAGE_ID));
            }
            return buildErrorResponse(e, HttpStatus.SC_BAD_REQUEST, e.getErrorResponse());
        } catch (JsonProcessingException | SqsException e) {
            return buildErrorResponse(
                    e,
                    HttpStatus.SC_INTERNAL_SERVER_ERROR,
                    ErrorResponse.FAILED_TO_SEND_AUDIT_EVENT);
        } catch (ParseException e) {
            return buildErrorResponse(
                    e,
                    HttpStatus.SC_INTERNAL_SERVER_ERROR,
                    ErrorResponse.FAILED_TO_PARSE_ISSUED_CREDENTIALS);
        } catch (VerifiableCredentialException e) {
            // Removed check for DCMAW because output is now equal
            return buildErrorResponse(e, e.getHttpStatusCode(), e.getErrorResponse());
        } catch (VerifiableCredentialResponseException e) {
            return buildErrorResponse(e, HttpStatus.SC_INTERNAL_SERVER_ERROR, e.getErrorResponse());
        } catch (CiPutException | CiPostMitigationsException e) {
            return buildErrorResponse(
                    e,
                    HttpStatus.SC_INTERNAL_SERVER_ERROR,
                    ErrorResponse.FAILED_TO_SAVE_CREDENTIAL);
        } catch (CiRetrievalException e) {
            return buildErrorResponse(
                    e, HttpStatus.SC_INTERNAL_SERVER_ERROR, ErrorResponse.FAILED_TO_GET_STORED_CIS);
        } catch (ConfigException e) {
            return buildErrorResponse(
                    e, HttpStatus.SC_INTERNAL_SERVER_ERROR, ErrorResponse.FAILED_TO_PARSE_CONFIG);
        } catch (CredentialParseException e) {
            return buildErrorResponse(
                    e,
                    HttpStatus.SC_INTERNAL_SERVER_ERROR,
                    ErrorResponse.FAILED_TO_PARSE_SUCCESSFUL_VC_STORE_ITEMS);
        } catch (CriApiException e) {
            return buildErrorResponse(e, e.getHttpStatusCode(), e.getErrorResponse());
        }
    }

    private CriCallbackRequest parseCallbackRequest(APIGatewayProxyRequestEvent input)
            throws ParseCriCallbackRequestException, InvalidCriCallbackRequestException {
        try {
            return objectMapper.readValue(input.getBody(), CriCallbackRequest.class);
        } catch (JsonProcessingException e) {
            throw new ParseCriCallbackRequestException(e);
        }
    }

    public JourneyResponse getJourneyResponse(CriCallbackRequest callbackRequest)
            throws SqsException, ParseException, JsonProcessingException,
                    HttpResponseExceptionWithErrorBody, ConfigException, CiRetrievalException,
                    CriApiException, VerifiableCredentialResponseException,
                    VerifiableCredentialException, CiPostMitigationsException, CiPutException,
                    CredentialParseException {
        IpvSessionItem ipvSessionItem = null;

        try {
            // Get/ set session items/ config
            ipvSessionItem = ipvSessionService.getIpvSession(callbackRequest.getIpvSessionId());
            var clientOAuthSessionItem =
                    clientOAuthSessionDetailsService.getClientOAuthSession(
                            ipvSessionItem.getClientOAuthSessionId());
            var criOAuthSessionItem =
                    criOAuthSessionService.getCriOauthSessionItem(callbackRequest.getState());
            criCheckingService.validateCriOauthSessionItem(criOAuthSessionItem, callbackRequest);
            configService.setFeatureSet(callbackRequest.getFeatureSet());

            // Attach variables to logs
            LogHelper.attachGovukSigninJourneyIdToLogs(
                    clientOAuthSessionItem.getGovukSigninJourneyId());
            LogHelper.attachIpvSessionIdToLogs(callbackRequest.getIpvSessionId());
            LogHelper.attachFeatureSetToLogs(callbackRequest.getFeatureSet());
            LogHelper.attachCriIdToLogs(callbackRequest.getCredentialIssuerId());
            LogHelper.attachComponentIdToLogs(configService);

            // Check for callback error
            if (callbackRequest.getError() != null) {
                return criCheckingService.handleCallbackError(
                        callbackRequest, clientOAuthSessionItem, ipvSessionItem);
            }

            // Retrieve, store and check cri credentials
            var accessToken = criApiService.fetchAccessToken(callbackRequest, criOAuthSessionItem);
            var vcResponse =
                    criApiService.fetchVerifiableCredential(
                            accessToken, callbackRequest, criOAuthSessionItem);

            criCheckingService.validateVcResponse(
                    vcResponse, clientOAuthSessionItem, criOAuthSessionItem);

            switch (vcResponse.getCredentialStatus()) {
                case CREATED -> criStoringService.storeCreatedVcs(
                        vcResponse, callbackRequest, clientOAuthSessionItem);

                case PENDING -> criStoringService.storeCriResponse(
                        callbackRequest, clientOAuthSessionItem);
            }

            return criCheckingService.checkVcResponse(
                    vcResponse, callbackRequest, clientOAuthSessionItem, ipvSessionItem);
        } finally {
            if (ipvSessionItem != null) {
                ipvSessionService.updateIpvSession(ipvSessionItem);
            }
        }
    }

    private APIGatewayProxyResponseEvent buildErrorResponse(
            Exception e, int status, ErrorResponse errorResponse) {
        LOGGER.error(errorResponse.getMessage(), e);
        return ApiGatewayResponseGenerator.proxyJsonResponse(
                status,
                new JourneyErrorResponse(
                        JOURNEY_ERROR_PATH, status, errorResponse, e.getMessage()));
    }
}
