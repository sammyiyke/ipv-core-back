package uk.gov.di.ipv.core.evaluategpg45scores;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.nimbusds.jose.shaded.json.JSONArray;
import com.nimbusds.jose.shaded.json.JSONObject;
import com.nimbusds.jwt.SignedJWT;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.StringMapMessage;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.tracing.Tracing;
import uk.gov.di.ipv.core.library.annotations.ExcludeFromGeneratedCoverageReport;
import uk.gov.di.ipv.core.library.auditing.AuditEvent;
import uk.gov.di.ipv.core.library.auditing.AuditEventTypes;
import uk.gov.di.ipv.core.library.auditing.AuditEventUser;
import uk.gov.di.ipv.core.library.auditing.AuditExtensionGpg45ProfileMatched;
import uk.gov.di.ipv.core.library.config.ConfigurationVariable;
import uk.gov.di.ipv.core.library.domain.ContraIndicatorItem;
import uk.gov.di.ipv.core.library.domain.ErrorResponse;
import uk.gov.di.ipv.core.library.domain.JourneyResponse;
import uk.gov.di.ipv.core.library.domain.gpg45.Gpg45Profile;
import uk.gov.di.ipv.core.library.domain.gpg45.Gpg45ProfileEvaluator;
import uk.gov.di.ipv.core.library.domain.gpg45.Gpg45Scores;
import uk.gov.di.ipv.core.library.domain.gpg45.exception.UnknownEvidenceTypeException;
import uk.gov.di.ipv.core.library.dto.ClientSessionDetailsDto;
import uk.gov.di.ipv.core.library.dto.ContraIndicatorMitigationDetailsDto;
import uk.gov.di.ipv.core.library.dto.CredentialIssuerConfig;
import uk.gov.di.ipv.core.library.dto.VcStatusDto;
import uk.gov.di.ipv.core.library.exceptions.CiRetrievalException;
import uk.gov.di.ipv.core.library.exceptions.HttpResponseExceptionWithErrorBody;
import uk.gov.di.ipv.core.library.exceptions.SqsException;
import uk.gov.di.ipv.core.library.helpers.ApiGatewayResponseGenerator;
import uk.gov.di.ipv.core.library.helpers.LogHelper;
import uk.gov.di.ipv.core.library.helpers.RequestHelper;
import uk.gov.di.ipv.core.library.helpers.VcHelper;
import uk.gov.di.ipv.core.library.persistence.item.IpvSessionItem;
import uk.gov.di.ipv.core.library.service.AuditService;
import uk.gov.di.ipv.core.library.service.CiStorageService;
import uk.gov.di.ipv.core.library.service.ConfigurationService;
import uk.gov.di.ipv.core.library.service.IpvSessionService;
import uk.gov.di.ipv.core.library.service.UserIdentityService;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.ADDRESS_CRI_ID;
import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.CI_MITIGATION_JOURNEYS_ENABLED;
import static uk.gov.di.ipv.core.library.domain.VerifiableCredentialConstants.VC_CLAIM;
import static uk.gov.di.ipv.core.library.domain.VerifiableCredentialConstants.VC_EVIDENCE;
import static uk.gov.di.ipv.core.library.domain.VerifiableCredentialConstants.VC_EVIDENCE_TXN;

public class EvaluateGpg45ScoresHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    public static final List<Gpg45Profile> ACCEPTED_PROFILES =
            List.of(Gpg45Profile.M1A, Gpg45Profile.M1B);
    public static final JourneyResponse JOURNEY_END = new JourneyResponse("/journey/end");
    public static final JourneyResponse JOURNEY_NEXT = new JourneyResponse("/journey/next");
    public static final String VOT_P2 = "P2";
    private static final Logger LOGGER = LogManager.getLogger();
    public static final int ONLY = 0;
    private final UserIdentityService userIdentityService;
    private final IpvSessionService ipvSessionService;
    private final Gpg45ProfileEvaluator gpg45ProfileEvaluator;
    private final CiStorageService ciStorageService;
    private final ConfigurationService configurationService;
    private final AuditService auditService;
    private final String addressCriId;
    private final String componentId;

    public EvaluateGpg45ScoresHandler(
            UserIdentityService userIdentityService,
            IpvSessionService ipvSessionService,
            Gpg45ProfileEvaluator gpg45ProfileEvaluator,
            CiStorageService ciStorageService,
            ConfigurationService configurationService,
            AuditService auditService) {
        this.userIdentityService = userIdentityService;
        this.ipvSessionService = ipvSessionService;
        this.gpg45ProfileEvaluator = gpg45ProfileEvaluator;
        this.ciStorageService = ciStorageService;
        this.configurationService = configurationService;
        this.auditService = auditService;

        addressCriId = configurationService.getSsmParameter(ADDRESS_CRI_ID);
        componentId =
                configurationService.getSsmParameter(ConfigurationVariable.AUDIENCE_FOR_CLIENTS);
    }

    @ExcludeFromGeneratedCoverageReport
    public EvaluateGpg45ScoresHandler() {
        this.configurationService = new ConfigurationService();
        this.userIdentityService = new UserIdentityService(configurationService);
        this.ipvSessionService = new IpvSessionService(configurationService);
        this.gpg45ProfileEvaluator = new Gpg45ProfileEvaluator(configurationService);
        this.ciStorageService = new CiStorageService(configurationService);
        this.auditService =
                new AuditService(AuditService.getDefaultSqsClient(), configurationService);

        addressCriId = configurationService.getSsmParameter(ADDRESS_CRI_ID);
        componentId =
                configurationService.getSsmParameter(ConfigurationVariable.AUDIENCE_FOR_CLIENTS);
    }

    @Override
    @Tracing
    @Logging(clearState = true)
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent event, Context context) {
        LogHelper.attachComponentIdToLogs();

        try {
            String ipvSessionId = RequestHelper.getIpvSessionId(event);
            String ipAddress = RequestHelper.getIpAddress(event);
            IpvSessionItem ipvSessionItem = ipvSessionService.getIpvSession(ipvSessionId);
            ClientSessionDetailsDto clientSessionDetailsDto =
                    ipvSessionItem.getClientSessionDetails();
            String userId = clientSessionDetailsDto.getUserId();

            String govukSigninJourneyId = clientSessionDetailsDto.getGovukSigninJourneyId();
            LogHelper.attachGovukSigninJourneyIdToLogs(govukSigninJourneyId);

            List<SignedJWT> credentials =
                    gpg45ProfileEvaluator.parseCredentials(
                            userIdentityService.getUserIssuedCredentials(userId));

            List<ContraIndicatorItem> ciItems;
            ciItems =
                    ciStorageService.getCIs(
                            clientSessionDetailsDto.getUserId(),
                            clientSessionDetailsDto.getGovukSigninJourneyId(),
                            ipAddress);

            JourneyResponse journeyResponse;
            var message = new StringMapMessage();

            boolean ciMitigationJourneysEnabled =
                    Boolean.parseBoolean(
                            configurationService.getSsmParameter(CI_MITIGATION_JOURNEYS_ENABLED));
            Optional<JourneyResponse> mitigationJourneyResponse = Optional.empty();
            if (ciMitigationJourneysEnabled) {
                mitigationJourneyResponse =
                        getNextMitigationJourneyResponse(ipvSessionItem, ciItems);
            }

            if (mitigationJourneyResponse.isPresent()) {
                journeyResponse = mitigationJourneyResponse.get();
            } else {
                Optional<JourneyResponse> contraIndicatorErrorJourneyResponse =
                        gpg45ProfileEvaluator.getJourneyResponseForStoredCis(ciItems);
                if (contraIndicatorErrorJourneyResponse.isEmpty()) {
                    journeyResponse =
                            checkForMatchingGpg45Profile(
                                    message, ipvSessionItem, credentials, ipAddress);
                } else {
                    return ApiGatewayResponseGenerator.proxyJsonResponse(
                            HttpStatus.SC_OK, contraIndicatorErrorJourneyResponse.get());
                }
            }

            updateSuccessfulVcStatuses(ipvSessionItem, credentials);

            LOGGER.info(message);

            return ApiGatewayResponseGenerator.proxyJsonResponse(HttpStatus.SC_OK, journeyResponse);
        } catch (HttpResponseExceptionWithErrorBody e) {
            return ApiGatewayResponseGenerator.proxyJsonResponse(
                    e.getResponseCode(), e.getErrorBody());
        } catch (ParseException e) {
            LOGGER.error("Unable to parse GPG45 scores from existing credentials", e);
            return ApiGatewayResponseGenerator.proxyJsonResponse(
                    HttpStatus.SC_INTERNAL_SERVER_ERROR,
                    ErrorResponse.FAILED_TO_PARSE_ISSUED_CREDENTIALS);
        } catch (UnknownEvidenceTypeException e) {
            LOGGER.error("Unable to determine type of credential", e);
            return ApiGatewayResponseGenerator.proxyJsonResponse(
                    HttpStatus.SC_INTERNAL_SERVER_ERROR,
                    ErrorResponse.FAILED_TO_DETERMINE_CREDENTIAL_TYPE);
        } catch (CiRetrievalException e) {
            LOGGER.error("Error when fetching CIs from storage system", e);
            return ApiGatewayResponseGenerator.proxyJsonResponse(
                    HttpStatus.SC_INTERNAL_SERVER_ERROR, ErrorResponse.FAILED_TO_GET_STORED_CIS);
        } catch (SqsException e) {
            LOGGER.error("Failed to send audit event to SQS queue: {}", e.getMessage());
            return ApiGatewayResponseGenerator.proxyJsonResponse(
                    HttpStatus.SC_INTERNAL_SERVER_ERROR, ErrorResponse.FAILED_TO_SEND_AUDIT_EVENT);
        }
    }

    private JourneyResponse checkForMatchingGpg45Profile(
            StringMapMessage message,
            IpvSessionItem ipvSessionItem,
            List<SignedJWT> credentials,
            String ipAddress)
            throws UnknownEvidenceTypeException, ParseException, SqsException {
        Gpg45Scores gpg45Scores = gpg45ProfileEvaluator.buildScore(credentials);
        Optional<Gpg45Profile> matchedProfile =
                gpg45ProfileEvaluator.getFirstMatchingProfile(gpg45Scores, ACCEPTED_PROFILES);

        if (matchedProfile.isPresent()) {
            auditService.sendAuditEvent(
                    buildProfileMatchedAuditEvent(
                            ipvSessionItem,
                            matchedProfile.get(),
                            gpg45Scores,
                            credentials,
                            ipAddress));
            ipvSessionItem.setVot(VOT_P2);

            message.with("lambdaResult", "A GPG45 profile has been met")
                    .with("journeyResponse", JOURNEY_END);
            return JOURNEY_END;
        } else {

            message.with("lambdaResult", "No GPG45 profiles have been met")
                    .with("journeyResponse", JOURNEY_NEXT);
            return JOURNEY_NEXT;
        }
    }

    private Optional<JourneyResponse> getNextMitigationJourneyResponse(
            IpvSessionItem ipvSessionItem, List<ContraIndicatorItem> ciItems) {
        List<ContraIndicatorMitigationDetailsDto> currentMitigationDetails =
                ipvSessionItem.getContraIndicatorMitigationDetails();
        boolean ciMitigationInProgress = isCiMitigationInProgress(currentMitigationDetails);

        List<ContraIndicatorItem> newCiItems =
                ciItems.stream()
                        .filter(
                                ciItem -> {
                                    if (currentMitigationDetails != null) {
                                        Optional<ContraIndicatorMitigationDetailsDto> matchingCI =
                                                currentMitigationDetails.stream()
                                                        .filter(
                                                                mitigationDetails ->
                                                                        mitigationDetails
                                                                                .getCi()
                                                                                .equals(
                                                                                        ciItem
                                                                                                .getCi()))
                                                        .findAny();
                                        return matchingCI.isEmpty();
                                    }
                                    return true;
                                })
                        .collect(Collectors.toList());

        if (!newCiItems.isEmpty()) {
            List<ContraIndicatorMitigationDetailsDto> newMitigationDetails = new ArrayList<>();

            if (ipvSessionItem.getContraIndicatorMitigationDetails() != null) {
                newMitigationDetails.addAll(ipvSessionItem.getContraIndicatorMitigationDetails());
            }

            newCiItems.forEach(
                    contraIndicatorItem ->
                            newMitigationDetails.add(
                                    new ContraIndicatorMitigationDetailsDto(
                                            contraIndicatorItem.getCi())));

            ipvSessionItem.setContraIndicatorMitigationDetails(newMitigationDetails);
            ipvSessionService.updateIpvSession(ipvSessionItem);

            return Optional.of(JOURNEY_NEXT);
        } else if (ciMitigationInProgress) {
            return Optional.of(JOURNEY_NEXT);
        }
        return Optional.empty();
    }

    @Tracing
    private void updateSuccessfulVcStatuses(
            IpvSessionItem ipvSessionItem, List<SignedJWT> credentials) throws ParseException {

        // get list of success vc's
        List<VcStatusDto> currentVcStatusDtos = ipvSessionItem.getCurrentVcStatuses();

        if (currentVcStatusDtos == null) {
            currentVcStatusDtos = new ArrayList<>();
        }

        if (currentVcStatusDtos.size() != credentials.size()) {
            List<VcStatusDto> updatedStatuses = generateVcSuccessStatuses(credentials);
            ipvSessionItem.setCurrentVcStatuses(updatedStatuses);
            ipvSessionService.updateIpvSession(ipvSessionItem);
        }
    }

    private List<VcStatusDto> generateVcSuccessStatuses(List<SignedJWT> credentials)
            throws ParseException {
        List<VcStatusDto> vcStatuses = new ArrayList<>();

        for (SignedJWT signedJWT : credentials) {

            CredentialIssuerConfig addressCriConfig =
                    configurationService.getCredentialIssuer(addressCriId);
            boolean isSuccessful = VcHelper.isSuccessfulVcIgnoringCi(signedJWT, addressCriConfig);

            vcStatuses.add(new VcStatusDto(signedJWT.getJWTClaimsSet().getIssuer(), isSuccessful));
        }
        return vcStatuses;
    }

    private AuditEvent buildProfileMatchedAuditEvent(
            IpvSessionItem ipvSessionItem,
            Gpg45Profile gpg45Profile,
            Gpg45Scores gpg45Scores,
            List<SignedJWT> credentials,
            String ipAddress)
            throws ParseException {
        AuditEventUser auditEventUser =
                new AuditEventUser(
                        ipvSessionItem.getClientSessionDetails().getUserId(),
                        ipvSessionItem.getIpvSessionId(),
                        ipvSessionItem.getClientSessionDetails().getGovukSigninJourneyId(),
                        ipAddress);
        return new AuditEvent(
                AuditEventTypes.IPV_GPG45_PROFILE_MATCHED,
                componentId,
                auditEventUser,
                new AuditExtensionGpg45ProfileMatched(
                        gpg45Profile, gpg45Scores, extractTxnIdsFromCredentials(credentials)));
    }

    private List<String> extractTxnIdsFromCredentials(List<SignedJWT> credentials)
            throws ParseException {
        List<String> txnIds = new ArrayList<>();
        for (SignedJWT credential : credentials) {
            var jwtClaimsSet = credential.getJWTClaimsSet();
            var vc = (JSONObject) jwtClaimsSet.getClaim(VC_CLAIM);
            var evidences = (JSONArray) vc.get(VC_EVIDENCE);
            if (evidences != null) { // not all VCs have an evidence block
                var evidence = (JSONObject) evidences.get(ONLY);
                txnIds.add(evidence.getAsString(VC_EVIDENCE_TXN));
            }
        }
        return txnIds;
    }

    private boolean isCiMitigationInProgress(
            List<ContraIndicatorMitigationDetailsDto> currentMitigationDetails) {
        if (currentMitigationDetails != null) {
            Optional<ContraIndicatorMitigationDetailsDto> inProgressCiMitigation =
                    currentMitigationDetails.stream()
                            .filter(ContraIndicatorMitigationDetailsDto::isMitigatable)
                            .findAny();
            return inProgressCiMitigation.isPresent();
        }
        return false;
    }
}
