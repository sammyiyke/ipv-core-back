package uk.gov.di.ipv.core.selectcri;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.nimbusds.jose.shaded.json.JSONArray;
import com.nimbusds.jose.shaded.json.JSONObject;
import com.nimbusds.jwt.SignedJWT;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.tracing.Tracing;
import uk.gov.di.ipv.core.library.annotations.ExcludeFromGeneratedCoverageReport;
import uk.gov.di.ipv.core.library.domain.ErrorResponse;
import uk.gov.di.ipv.core.library.domain.JourneyResponse;
import uk.gov.di.ipv.core.library.domain.gpg45.Gpg45Scores;
import uk.gov.di.ipv.core.library.domain.gpg45.domain.CredentialEvidenceItem;
import uk.gov.di.ipv.core.library.dto.ClientSessionDetailsDto;
import uk.gov.di.ipv.core.library.dto.VisitedCredentialIssuerDetailsDto;
import uk.gov.di.ipv.core.library.exceptions.HttpResponseExceptionWithErrorBody;
import uk.gov.di.ipv.core.library.helpers.ApiGatewayResponseGenerator;
import uk.gov.di.ipv.core.library.helpers.LogHelper;
import uk.gov.di.ipv.core.library.helpers.RequestHelper;
import uk.gov.di.ipv.core.library.persistence.item.IpvSessionItem;
import uk.gov.di.ipv.core.library.persistence.item.UserIssuedCredentialsItem;
import uk.gov.di.ipv.core.library.service.ConfigurationService;
import uk.gov.di.ipv.core.library.service.IpvSessionService;
import uk.gov.di.ipv.core.library.service.UserIdentityService;

import java.text.ParseException;
import java.util.List;
import java.util.Optional;

import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.ADDRESS_CRI_ID;
import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.DCMAW_CRI_ID;
import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.DCMAW_ENABLED;
import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.FRAUD_CRI_ID;
import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.KBV_CRI_ID;
import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.PASSPORT_CRI_ID;
import static uk.gov.di.ipv.core.library.domain.VerifiableCredentialConstants.VC_CLAIM;
import static uk.gov.di.ipv.core.library.domain.VerifiableCredentialConstants.VC_EVIDENCE;

public class SelectCriHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson gson = new Gson();
    private static final String CRI_START_JOURNEY = "/journey/%s";
    public static final String JOURNEY_FAIL = "/journey/fail";

    private final ConfigurationService configurationService;
    private final UserIdentityService userIdentityService;
    private final IpvSessionService ipvSessionService;
    private final String passportCriId;
    private final String fraudCriId;
    private final String kbvCriId;
    private final String addressCriId;
    private final String dcmawCriId;

    public SelectCriHandler(
            ConfigurationService configurationService,
            UserIdentityService userIdentityService,
            IpvSessionService ipvSessionService) {
        this.configurationService = configurationService;
        this.userIdentityService = userIdentityService;
        this.ipvSessionService = ipvSessionService;

        passportCriId = configurationService.getSsmParameter(PASSPORT_CRI_ID);
        fraudCriId = configurationService.getSsmParameter(FRAUD_CRI_ID);
        kbvCriId = configurationService.getSsmParameter(KBV_CRI_ID);
        addressCriId = configurationService.getSsmParameter(ADDRESS_CRI_ID);
        dcmawCriId = configurationService.getSsmParameter(DCMAW_CRI_ID);
    }

    @ExcludeFromGeneratedCoverageReport
    public SelectCriHandler() {
        this.configurationService = new ConfigurationService();
        this.userIdentityService = new UserIdentityService(configurationService);
        this.ipvSessionService = new IpvSessionService(configurationService);

        passportCriId = configurationService.getSsmParameter(PASSPORT_CRI_ID);
        fraudCriId = configurationService.getSsmParameter(FRAUD_CRI_ID);
        kbvCriId = configurationService.getSsmParameter(KBV_CRI_ID);
        addressCriId = configurationService.getSsmParameter(ADDRESS_CRI_ID);
        dcmawCriId = configurationService.getSsmParameter(DCMAW_CRI_ID);
    }

    @Override
    @Tracing
    @Logging(clearState = true)
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent event, Context context) {
        LogHelper.attachComponentIdToLogs();
        try {
            String ipvSessionId = RequestHelper.getIpvSessionId(event);
            IpvSessionItem ipvSessionItem = ipvSessionService.getIpvSession(ipvSessionId);

            logGovUkSignInJourneyId(ipvSessionId);

            List<VisitedCredentialIssuerDetailsDto> visitedCredentialIssuers =
                    ipvSessionItem.getVisitedCredentialIssuerDetails();

            boolean dcmawEnabled =
                    Boolean.parseBoolean(configurationService.getSsmParameter(DCMAW_ENABLED));

            if (dcmawEnabled) {
                return getNextAppJourneyCri(
                        visitedCredentialIssuers,
                        ipvSessionItem.getClientSessionDetails().getUserId());
            } else {
                return getNextWebJourneyCri(visitedCredentialIssuers);
            }
        } catch (HttpResponseExceptionWithErrorBody e) {
            return ApiGatewayResponseGenerator.proxyJsonResponse(
                    e.getResponseCode(), e.getErrorBody());
        } catch (ParseException e) {
            LOGGER.error("Unable to parse existing credentials", e);
            return ApiGatewayResponseGenerator.proxyJsonResponse(
                    HttpStatus.SC_INTERNAL_SERVER_ERROR,
                    ErrorResponse.FAILED_TO_PARSE_ISSUED_CREDENTIALS);
        }
    }

    private APIGatewayProxyResponseEvent getNextWebJourneyCri(
            List<VisitedCredentialIssuerDetailsDto> visitedCredentialIssuers) {
        if (userHasNotVisited(visitedCredentialIssuers, passportCriId)) {
            return getJourneyResponse(passportCriId);
        }

        if (userHasNotVisited(visitedCredentialIssuers, addressCriId)) {
            return getJourneyResponse(addressCriId);
        }

        if (userHasNotVisited(visitedCredentialIssuers, fraudCriId)) {
            return getJourneyResponse(fraudCriId);
        }

        if (userHasNotVisited(visitedCredentialIssuers, kbvCriId)) {
            return getJourneyResponse(kbvCriId);
        }

        LOGGER.info("Unable to determine next credential issuer");
        return ApiGatewayResponseGenerator.proxyJsonResponse(
                HttpStatus.SC_OK, new JourneyResponse(JOURNEY_FAIL));
    }

    private APIGatewayProxyResponseEvent getNextAppJourneyCri(
            List<VisitedCredentialIssuerDetailsDto> visitedCredentialIssuers, String userId)
            throws ParseException {
        if (userHasNotVisited(visitedCredentialIssuers, dcmawCriId)) {
            return getJourneyResponse(dcmawCriId);
        } else {
            Optional<VisitedCredentialIssuerDetailsDto> dcmawVisitDetails =
                    visitedCredentialIssuers.stream()
                            .filter(cri -> cri.getCriId().equals(dcmawCriId))
                            .findFirst();

            if (dcmawVisitDetails.isPresent()) {
                if (dcmawVisitDetails.get().isReturnedWithVc()) {
                    UserIssuedCredentialsItem userIssuedCredentialsItem =
                            userIdentityService.getUserIssuedCredential(userId, dcmawCriId);

                    JSONObject vcClaim =
                            (JSONObject)
                                    SignedJWT.parse(userIssuedCredentialsItem.getCredential())
                                            .getJWTClaimsSet()
                                            .getClaim(VC_CLAIM);
                    JSONArray evidenceArray = (JSONArray) vcClaim.get(VC_EVIDENCE);

                    List<CredentialEvidenceItem> credentialEvidenceList =
                            gson.fromJson(
                                    evidenceArray.toJSONString(),
                                    new TypeToken<List<CredentialEvidenceItem>>() {}.getType());

                    if (!credentialEvidenceList
                            .get(0)
                            .getEvidenceScore()
                            .equals(Gpg45Scores.EV_32)) {
                        LOGGER.info(
                                "User has a previous failed visit to dcmaw due to a failed identity check. Routing user to web journey instead");
                        return getNextWebJourneyCri(visitedCredentialIssuers);
                    }
                } else {
                    LOGGER.info(
                            "User has a previous failed visit to dcmaw due to: {}. Routing user to web journey instead.",
                            dcmawVisitDetails.get().getOauthError());
                    return getNextWebJourneyCri(visitedCredentialIssuers);
                }
            }
        }

        if (userHasNotVisited(visitedCredentialIssuers, addressCriId)) {
            return getJourneyResponse(addressCriId);
        }

        if (userHasNotVisited(visitedCredentialIssuers, fraudCriId)) {
            return getJourneyResponse(fraudCriId);
        }

        LOGGER.info("Unable to determine next credential issuer");
        return ApiGatewayResponseGenerator.proxyJsonResponse(
                HttpStatus.SC_OK, new JourneyResponse(JOURNEY_FAIL));
    }

    private void logGovUkSignInJourneyId(String ipvSessionId) {
        IpvSessionItem ipvSessionItem = ipvSessionService.getIpvSession(ipvSessionId);
        ClientSessionDetailsDto clientSessionDetailsDto = ipvSessionItem.getClientSessionDetails();
        LogHelper.attachGovukSigninJourneyIdToLogs(
                clientSessionDetailsDto.getGovukSigninJourneyId());
    }

    private APIGatewayProxyResponseEvent getJourneyResponse(String criId) {
        return ApiGatewayResponseGenerator.proxyJsonResponse(
                HttpStatus.SC_OK, new JourneyResponse(String.format(CRI_START_JOURNEY, criId)));
    }

    private boolean userHasNotVisited(
            List<VisitedCredentialIssuerDetailsDto> visitedCredentialIssuers, String criId) {
        return visitedCredentialIssuers.stream().noneMatch(cri -> cri.getCriId().equals(criId));
    }
}
