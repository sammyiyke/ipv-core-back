package uk.gov.di.ipv.core.processcricallback.service;

import com.nimbusds.oauth2.sdk.OAuth2Error;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.util.CollectionUtils;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.gov.di.ipv.core.library.annotations.ExcludeFromGeneratedCoverageReport;
import uk.gov.di.ipv.core.library.auditing.AuditEvent;
import uk.gov.di.ipv.core.library.auditing.AuditEventTypes;
import uk.gov.di.ipv.core.library.auditing.AuditEventUser;
import uk.gov.di.ipv.core.library.auditing.extension.AuditExtensionErrorParams;
import uk.gov.di.ipv.core.library.cimit.exception.CiRetrievalException;
import uk.gov.di.ipv.core.library.config.ConfigurationVariable;
import uk.gov.di.ipv.core.library.domain.ErrorResponse;
import uk.gov.di.ipv.core.library.domain.JourneyResponse;
import uk.gov.di.ipv.core.library.dto.VisitedCredentialIssuerDetailsDto;
import uk.gov.di.ipv.core.library.exceptions.*;
import uk.gov.di.ipv.core.library.helpers.LogHelper;
import uk.gov.di.ipv.core.library.persistence.item.ClientOAuthSessionItem;
import uk.gov.di.ipv.core.library.persistence.item.CriOAuthSessionItem;
import uk.gov.di.ipv.core.library.persistence.item.IpvSessionItem;
import uk.gov.di.ipv.core.library.service.AuditService;
import uk.gov.di.ipv.core.library.service.CiMitService;
import uk.gov.di.ipv.core.library.service.ConfigService;
import uk.gov.di.ipv.core.library.service.UserIdentityService;
import uk.gov.di.ipv.core.library.verifiablecredential.domain.VerifiableCredentialResponse;
import uk.gov.di.ipv.core.library.verifiablecredential.exception.VerifiableCredentialResponseException;
import uk.gov.di.ipv.core.library.verifiablecredential.helpers.VcHelper;
import uk.gov.di.ipv.core.library.verifiablecredential.validator.VerifiableCredentialJwtValidator;
import uk.gov.di.ipv.core.processcricallback.dto.CriCallbackRequest;
import uk.gov.di.ipv.core.processcricallback.exception.InvalidCriCallbackRequestException;

import java.text.ParseException;
import java.util.Arrays;
import java.util.List;

import static uk.gov.di.ipv.core.library.domain.ErrorResponse.FAILED_TO_VALIDATE_VERIFIABLE_CREDENTIAL_RESPONSE;
import static uk.gov.di.ipv.core.library.journeyuris.JourneyUris.JOURNEY_ACCESS_DENIED_PATH;
import static uk.gov.di.ipv.core.library.journeyuris.JourneyUris.JOURNEY_ERROR_PATH;
import static uk.gov.di.ipv.core.library.journeyuris.JourneyUris.JOURNEY_FAIL_WITH_NO_CI_PATH;
import static uk.gov.di.ipv.core.library.journeyuris.JourneyUris.JOURNEY_NEXT_PATH;
import static uk.gov.di.ipv.core.library.journeyuris.JourneyUris.JOURNEY_PYI_NO_MATCH_PATH;
import static uk.gov.di.ipv.core.library.journeyuris.JourneyUris.JOURNEY_TEMPORARILY_UNAVAILABLE_PATH;

public class CriCheckingService {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final JourneyResponse JOURNEY_NEXT = new JourneyResponse(JOURNEY_NEXT_PATH);
    private static final JourneyResponse JOURNEY_PYI_NO_MATCH =
            new JourneyResponse(JOURNEY_PYI_NO_MATCH_PATH);
    private static final JourneyResponse JOURNEY_FAIL_WITH_NO_CI =
            new JourneyResponse(JOURNEY_FAIL_WITH_NO_CI_PATH);
    private static final JourneyResponse JOURNEY_ACCESS_DENIED =
            new JourneyResponse(JOURNEY_ACCESS_DENIED_PATH);
    private static final JourneyResponse JOURNEY_TEMPORARILY_UNAVAILABLE =
            new JourneyResponse(JOURNEY_TEMPORARILY_UNAVAILABLE_PATH);
    private static final JourneyResponse JOURNEY_ERROR = new JourneyResponse(JOURNEY_ERROR_PATH);
    private static final List<String> ALLOWED_OAUTH_ERROR_CODES =
            Arrays.asList(
                    OAuth2Error.INVALID_REQUEST_CODE,
                    OAuth2Error.UNAUTHORIZED_CLIENT_CODE,
                    OAuth2Error.ACCESS_DENIED_CODE,
                    OAuth2Error.UNSUPPORTED_RESPONSE_TYPE_CODE,
                    OAuth2Error.INVALID_SCOPE_CODE,
                    OAuth2Error.SERVER_ERROR_CODE,
                    OAuth2Error.TEMPORARILY_UNAVAILABLE_CODE);
    private final UserIdentityService userIdentityService;
    private final AuditService auditService;
    private final CiMitService ciMitService;
    private final ConfigService configService;
    private final VerifiableCredentialJwtValidator verifiableCredentialJwtValidator;

    @ExcludeFromGeneratedCoverageReport
    public CriCheckingService(
            ConfigService configService,
            AuditService auditService,
            UserIdentityService userIdentityService,
            CiMitService ciMitService,
            VerifiableCredentialJwtValidator verifiableCredentialJwtValidator) {
        this.configService = configService;
        this.auditService = auditService;
        this.userIdentityService = userIdentityService;
        this.ciMitService = ciMitService;
        this.verifiableCredentialJwtValidator = verifiableCredentialJwtValidator;
    }

    public JourneyResponse handleCallbackError(
            CriCallbackRequest callbackRequest,
            ClientOAuthSessionItem clientOAuthSessionItem,
            IpvSessionItem ipvSessionItem)
            throws SqsException {
        var criId = callbackRequest.getCredentialIssuerId();
        var ipAddress = callbackRequest.getIpAddress();
        var error = callbackRequest.getError();
        var errorDescription = callbackRequest.getErrorDescription();
        var userId = clientOAuthSessionItem.getUserId();
        var govukSigninJourneyId = clientOAuthSessionItem.getGovukSigninJourneyId();

        var auditEventUser =
                new AuditEventUser(
                        userId, callbackRequest.getIpvSessionId(), govukSigninJourneyId, ipAddress);

        AuditExtensionErrorParams extensions =
                new AuditExtensionErrorParams.Builder()
                        .setErrorCode(error)
                        .setErrorDescription(errorDescription)
                        .build();

        auditService.sendAuditEvent(
                new AuditEvent(
                        AuditEventTypes.IPV_CRI_AUTH_RESPONSE_RECEIVED,
                        configService.getSsmParameter(ConfigurationVariable.COMPONENT_ID),
                        auditEventUser,
                        extensions));

        if (!ALLOWED_OAUTH_ERROR_CODES.contains(error)) {
            LOGGER.warn("Unknown Oauth error code received");
        }

        var visitedCriDetails = new VisitedCredentialIssuerDetailsDto(criId, null, false, error);
        ipvSessionItem.addVisitedCredentialIssuerDetails(visitedCriDetails);

        LogHelper.logCriOauthError("OAuth error received from CRI", error, errorDescription, criId);

        return (switch (error) {
            case OAuth2Error.ACCESS_DENIED_CODE -> JOURNEY_ACCESS_DENIED;
            case OAuth2Error.TEMPORARILY_UNAVAILABLE_CODE -> JOURNEY_TEMPORARILY_UNAVAILABLE;
            default -> JOURNEY_ERROR;
        });
    }

    public void validateCallbackRequest(CriCallbackRequest callbackRequest)
            throws InvalidCriCallbackRequestException {
        var criId = callbackRequest.getCredentialIssuerId();
        if (criId == null || criId.isBlank()) {
            throw new InvalidCriCallbackRequestException(
                    ErrorResponse.MISSING_CREDENTIAL_ISSUER_ID);
        }
        if (configService.getCredentialIssuerActiveConnectionConfig(criId) == null) {
            throw new InvalidCriCallbackRequestException(
                    ErrorResponse.INVALID_CREDENTIAL_ISSUER_ID);
        }

        var authorisationCode = callbackRequest.getAuthorizationCode();
        if (authorisationCode == null || authorisationCode.isBlank()) {
            throw new InvalidCriCallbackRequestException(ErrorResponse.MISSING_AUTHORIZATION_CODE);
        }

        var ipvSessionId = callbackRequest.getIpvSessionId();
        if (ipvSessionId == null || ipvSessionId.isBlank()) {
            throw new InvalidCriCallbackRequestException(ErrorResponse.MISSING_IPV_SESSION_ID);
        }

        var criOAuthSessionId = callbackRequest.getState();
        if (criOAuthSessionId == null || criOAuthSessionId.isBlank()) {
            throw new InvalidCriCallbackRequestException(ErrorResponse.MISSING_OAUTH_STATE);
        }
    }

    public void validateCriOauthSessionItem(
            CriOAuthSessionItem criOAuthSessionItem, CriCallbackRequest callbackRequest)
            throws HttpResponseExceptionWithErrorBody {
        if (criOAuthSessionItem == null) {
            throw new HttpResponseExceptionWithErrorBody(
                    HttpStatus.SC_BAD_REQUEST, ErrorResponse.INVALID_OAUTH_STATE);
        }

        if (!criOAuthSessionItem.getCriId().equals(callbackRequest.getCredentialIssuerId())) {
            throw new HttpResponseExceptionWithErrorBody(
                    HttpStatus.SC_BAD_REQUEST, ErrorResponse.INVALID_OAUTH_STATE);
        }
    }

    public void validateVcResponse(
            VerifiableCredentialResponse vcResponse,
            ClientOAuthSessionItem clientOAuthSessionItem,
            CriOAuthSessionItem criOAuthSessionItem)
            throws VerifiableCredentialResponseException, VerifiableCredentialException {
        var userId = clientOAuthSessionItem.getUserId();
        var criConfig = configService.getCriConfig(criOAuthSessionItem);

        if (!vcResponse.getUserId().equals(userId)) {
            throw new VerifiableCredentialResponseException(
                    HTTPResponse.SC_SERVER_ERROR,
                    FAILED_TO_VALIDATE_VERIFIABLE_CREDENTIAL_RESPONSE);
        }

        var vcs = vcResponse.getVerifiableCredentials();
        for (var vc : vcs) {
            verifiableCredentialJwtValidator.validate(vc, criConfig, userId);
        }
    }

    public JourneyResponse checkVcResponse(
            VerifiableCredentialResponse vcResponse,
            CriCallbackRequest callbackRequest,
            ClientOAuthSessionItem clientOAuthSessionItem,
            IpvSessionItem ipvSessionItem)
            throws CiRetrievalException, ConfigException, HttpResponseExceptionWithErrorBody,
                    ParseException, CredentialParseException {
        var ipAddress = callbackRequest.getIpAddress();
        var userId = clientOAuthSessionItem.getUserId();
        var govukSigninJourneyId = clientOAuthSessionItem.getGovukSigninJourneyId();

        var cis = ciMitService.getContraIndicatorsVC(userId, govukSigninJourneyId, ipAddress);

        if (userIdentityService.isBreachingCiThreshold(cis)) {
            ipvSessionItem.setCiFail(true); // TODO: Remove ciFail flag in PYIC-3797

            var cimitConfig = configService.getCimitConfig();
            for (var ci : cis.getContraIndicatorsMap().values()) {
                if (cimitConfig.containsKey(ci.getCode())
                        && CollectionUtils.isEmpty(ci.getMitigation())) {
                    return new JourneyResponse(cimitConfig.get(ci.getCode()));
                }
            }
            return JOURNEY_PYI_NO_MATCH;
        }
        ipvSessionItem.setCiFail(false);

        if (!userIdentityService.areVcsCorrelated(userId)) {
            return JOURNEY_PYI_NO_MATCH;
        }

        if (!VcHelper.isSuccessfulVcs(vcResponse.getVerifiableCredentials())) {
            return JOURNEY_FAIL_WITH_NO_CI;
        }

        return JOURNEY_NEXT;
    }
}
