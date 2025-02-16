package uk.gov.di.ipv.core.builduseridentity;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.OAuth2Error;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.oauth2.sdk.token.BearerTokenError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.di.ipv.core.library.auditing.AuditEvent;
import uk.gov.di.ipv.core.library.auditing.AuditEventTypes;
import uk.gov.di.ipv.core.library.auditing.extension.AuditExtensionsUserIdentity;
import uk.gov.di.ipv.core.library.cimit.exception.CiRetrievalException;
import uk.gov.di.ipv.core.library.domain.AuditEventReturnCode;
import uk.gov.di.ipv.core.library.domain.BirthDate;
import uk.gov.di.ipv.core.library.domain.ContraIndicatorConfig;
import uk.gov.di.ipv.core.library.domain.ContraIndicators;
import uk.gov.di.ipv.core.library.domain.ErrorResponse;
import uk.gov.di.ipv.core.library.domain.IdentityClaim;
import uk.gov.di.ipv.core.library.domain.Name;
import uk.gov.di.ipv.core.library.domain.NameParts;
import uk.gov.di.ipv.core.library.domain.ReturnCode;
import uk.gov.di.ipv.core.library.domain.UserIdentity;
import uk.gov.di.ipv.core.library.domain.VerifiableCredential;
import uk.gov.di.ipv.core.library.domain.cimitvc.ContraIndicator;
import uk.gov.di.ipv.core.library.dto.AccessTokenMetadata;
import uk.gov.di.ipv.core.library.enums.Vot;
import uk.gov.di.ipv.core.library.exceptions.CredentialParseException;
import uk.gov.di.ipv.core.library.exceptions.HttpResponseExceptionWithErrorBody;
import uk.gov.di.ipv.core.library.exceptions.UnrecognisedCiException;
import uk.gov.di.ipv.core.library.exceptions.VerifiableCredentialException;
import uk.gov.di.ipv.core.library.helpers.SecureTokenHelper;
import uk.gov.di.ipv.core.library.persistence.item.ClientOAuthSessionItem;
import uk.gov.di.ipv.core.library.persistence.item.IpvSessionItem;
import uk.gov.di.ipv.core.library.service.AuditService;
import uk.gov.di.ipv.core.library.service.CiMitService;
import uk.gov.di.ipv.core.library.service.CiMitUtilityService;
import uk.gov.di.ipv.core.library.service.ClientOAuthSessionDetailsService;
import uk.gov.di.ipv.core.library.service.ConfigService;
import uk.gov.di.ipv.core.library.service.IpvSessionService;
import uk.gov.di.ipv.core.library.service.UserIdentityService;
import uk.gov.di.ipv.core.library.verifiablecredential.service.SessionCredentialsService;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.lang.String.valueOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.di.ipv.core.library.config.CoreFeatureFlag.TICF_CRI_BETA;
import static uk.gov.di.ipv.core.library.domain.ErrorResponse.FAILED_TO_GET_CREDENTIAL;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.ADDRESS_JSON_1;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.DRIVING_PERMIT_JSON_1;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.NINO_JSON_1;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.PASSPORT_JSON_1;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.SIGNED_CONTRA_INDICATOR_VC;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.VC_ADDRESS;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.vcTicf;

@ExtendWith(MockitoExtension.class)
class BuildUserIdentityHandlerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String TEST_IPV_SESSION_ID = SecureTokenHelper.getInstance().generate();
    private static final String TEST_USER_ID = "test-user-id";
    private static final String TEST_ACCESS_TOKEN = "test-access-token";
    private static final String VTM = "http://www.example.com/vtm";
    private static final String TEST_IP_ADDRESS = "192.168.1.100";
    private static final String TEST_CLIENT_OAUTH_SESSION_ID =
            SecureTokenHelper.getInstance().generate();
    private static final ContraIndicators CONTRA_INDICATORS =
            ContraIndicators.builder()
                    .usersContraIndicators(
                            List.of(
                                    ContraIndicator.builder()
                                            .code("X01")
                                            .issuers(
                                                    List.of(
                                                            "https://review-d.account.gov.uk",
                                                            "https://review-f.account.gov.uk"))
                                            .build(),
                                    ContraIndicator.builder()
                                            .code("X02")
                                            .issuers(
                                                    List.of(
                                                            "https://review-q.account.gov.uk",
                                                            "https://review-f.account.gov.uk"))
                                            .build(),
                                    ContraIndicator.builder()
                                            .code("Z03")
                                            .issuers(
                                                    List.of(
                                                            "https://review-z.account.gov.uk",
                                                            "https://review-f.account.gov.uk"))
                                            .build()))
                    .build();
    private static final APIGatewayProxyRequestEvent testEvent = getEventWithAuthAndIpHeaders();

    @Mock private Context mockContext;
    @Mock private UserIdentityService mockUserIdentityService;
    @Mock private IpvSessionService mockIpvSessionService;
    @Mock private ConfigService mockConfigService;
    @Mock private AuditService mockAuditService;
    @Mock private ClientOAuthSessionDetailsService mockClientOAuthSessionDetailsService;
    @Mock private CiMitService mockCiMitService;
    @Mock private CiMitUtilityService mockCiMitUtilityService;
    @Mock private SessionCredentialsService mockSessionCredentialsService;
    @InjectMocks private BuildUserIdentityHandler buildUserIdentityHandler;

    private UserIdentity userIdentity;
    private IpvSessionItem ipvSessionItem;
    private ClientOAuthSessionItem clientOAuthSessionItem;
    private Map<String, String> responseBody;

    @BeforeEach
    void setUp() throws Exception {
        responseBody = new HashMap<>();

        List<Name> names =
                Collections.singletonList(
                        new Name(Collections.singletonList(new NameParts("GivenName", "Daniel"))));
        List<BirthDate> birthDates = Collections.singletonList(new BirthDate("1990-02-10"));

        userIdentity =
                new UserIdentity(
                        List.of("12345", "Test credential", "bar"),
                        new IdentityClaim(names, birthDates),
                        OBJECT_MAPPER.readTree(ADDRESS_JSON_1),
                        OBJECT_MAPPER.readTree(PASSPORT_JSON_1),
                        OBJECT_MAPPER.readTree(DRIVING_PERMIT_JSON_1),
                        OBJECT_MAPPER.readTree(NINO_JSON_1),
                        "test-sub",
                        Vot.P2,
                        VTM,
                        List.of(new ReturnCode("1"), new ReturnCode("2"), new ReturnCode("3")));

        ipvSessionItem = new IpvSessionItem();
        ipvSessionItem.setIpvSessionId(TEST_IPV_SESSION_ID);
        ipvSessionItem.setUserState("test-state");
        ipvSessionItem.setClientOAuthSessionId(TEST_CLIENT_OAUTH_SESSION_ID);
        ipvSessionItem.setAccessToken(TEST_ACCESS_TOKEN);
        ipvSessionItem.setAccessTokenMetadata(new AccessTokenMetadata());
        ipvSessionItem.setVot(Vot.P2);
        ipvSessionItem.setFeatureSet("someCoolNewThing");

        clientOAuthSessionItem =
                ClientOAuthSessionItem.builder()
                        .clientOAuthSessionId(TEST_CLIENT_OAUTH_SESSION_ID)
                        .state("test-state")
                        .responseType("code")
                        .redirectUri("https://example.com/redirect")
                        .govukSigninJourneyId("test-journey-id")
                        .userId(TEST_USER_ID)
                        .clientId("test-client")
                        .govukSigninJourneyId("test-journey-id")
                        .build();
    }

    @Test
    void shouldReturnCredentialsWithCiMitVCOnSuccessfulUserInfoRequest() throws Exception {
        // Arrange
        when(mockIpvSessionService.getIpvSessionByAccessToken(TEST_ACCESS_TOKEN))
                .thenReturn(Optional.ofNullable(ipvSessionItem));
        when(mockUserIdentityService.generateUserIdentity(any(), any(), any(), any()))
                .thenReturn(userIdentity);
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockCiMitService.getContraIndicatorsVc(any(), any(), any()))
                .thenReturn(
                        VerifiableCredential.fromValidJwt(
                                TEST_USER_ID,
                                "test-cri-id",
                                SignedJWT.parse(SIGNED_CONTRA_INDICATOR_VC)));
        ContraIndicators mockContraIndicators = mock(ContraIndicators.class);
        when(mockCiMitService.getContraIndicators(any())).thenReturn(mockContraIndicators);
        when(mockContraIndicators.hasMitigations()).thenReturn(true);
        when(mockConfigService.enabled(TICF_CRI_BETA)).thenReturn(false);
        when(mockSessionCredentialsService.getCredentials(TEST_IPV_SESSION_ID, TEST_USER_ID))
                .thenReturn(List.of(VC_ADDRESS));

        // Act
        APIGatewayProxyResponseEvent response =
                buildUserIdentityHandler.handleRequest(testEvent, mockContext);

        // Assert
        assertEquals(200, response.getStatusCode());

        UserIdentity responseBody =
                OBJECT_MAPPER.readValue(response.getBody(), new TypeReference<>() {});

        assertEquals(4, responseBody.getVcs().size());
        assertEquals(userIdentity.getVcs(), responseBody.getVcs());
        assertEquals(userIdentity.getIdentityClaim(), responseBody.getIdentityClaim());
        assertEquals(userIdentity.getAddressClaim(), responseBody.getAddressClaim());
        assertEquals(userIdentity.getDrivingPermitClaim(), responseBody.getDrivingPermitClaim());
        assertEquals(userIdentity.getNinoClaim(), responseBody.getNinoClaim());

        verify(mockIpvSessionService).revokeAccessToken(ipvSessionItem);

        ArgumentCaptor<AuditEvent> auditEventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(mockAuditService).sendAuditEvent(auditEventCaptor.capture());

        AuditEvent capturedAuditEvent = auditEventCaptor.getValue();
        assertEquals(AuditEventTypes.IPV_IDENTITY_ISSUED, capturedAuditEvent.getEventName());
        AuditExtensionsUserIdentity extensions =
                (AuditExtensionsUserIdentity) capturedAuditEvent.getExtensions();
        assertEquals(Vot.P2, extensions.getLevelOfConfidence());
        assertFalse(extensions.isCiFail());
        assertTrue(extensions.isHasMitigations());
        assertEquals(3, responseBody.getReturnCode().size());
        verify(mockClientOAuthSessionDetailsService, times(1)).getClientOAuthSession(any());
        verify(mockCiMitService, times(1)).getContraIndicatorsVc(any(), any(), any());

        verify(mockConfigService).setFeatureSet(List.of("someCoolNewThing"));

        verify(mockUserIdentityService)
                .generateUserIdentity(
                        List.of(VC_ADDRESS), TEST_USER_ID, Vot.P2, mockContraIndicators);

        verify(mockSessionCredentialsService, times(1))
                .deleteSessionCredentials(TEST_IPV_SESSION_ID);
    }

    @Test
    void
            shouldReturnCredentialsWithCiMitVCOnSuccessfulUserInfoRequestWhenDeleteSessionCredentialsError()
                    throws Exception {
        // Arrange
        when(mockIpvSessionService.getIpvSessionByAccessToken(TEST_ACCESS_TOKEN))
                .thenReturn(Optional.ofNullable(ipvSessionItem));
        when(mockUserIdentityService.generateUserIdentity(any(), any(), any(), any()))
                .thenReturn(userIdentity);
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockCiMitService.getContraIndicatorsVc(any(), any(), any()))
                .thenReturn(
                        VerifiableCredential.fromValidJwt(
                                TEST_USER_ID,
                                "test-cri-id",
                                SignedJWT.parse(SIGNED_CONTRA_INDICATOR_VC)));
        ContraIndicators mockContraIndicators = mock(ContraIndicators.class);
        when(mockCiMitService.getContraIndicators(any())).thenReturn(mockContraIndicators);
        when(mockContraIndicators.hasMitigations()).thenReturn(true);
        when(mockConfigService.enabled(TICF_CRI_BETA)).thenReturn(false);
        when(mockSessionCredentialsService.getCredentials(TEST_IPV_SESSION_ID, TEST_USER_ID))
                .thenReturn(List.of(VC_ADDRESS));
        doThrow(
                        new VerifiableCredentialException(
                                HTTPResponse.SC_SERVER_ERROR,
                                ErrorResponse.FAILED_TO_DELETE_CREDENTIAL))
                .when(mockSessionCredentialsService)
                .deleteSessionCredentials(any());
        // Act
        APIGatewayProxyResponseEvent response =
                buildUserIdentityHandler.handleRequest(testEvent, mockContext);

        // Assert
        assertEquals(200, response.getStatusCode());

        UserIdentity responseBody =
                OBJECT_MAPPER.readValue(response.getBody(), new TypeReference<>() {});

        assertEquals(4, responseBody.getVcs().size());
        assertEquals(userIdentity.getVcs(), responseBody.getVcs());
        assertEquals(userIdentity.getIdentityClaim(), responseBody.getIdentityClaim());
        assertEquals(userIdentity.getAddressClaim(), responseBody.getAddressClaim());
        assertEquals(userIdentity.getDrivingPermitClaim(), responseBody.getDrivingPermitClaim());
        assertEquals(userIdentity.getNinoClaim(), responseBody.getNinoClaim());

        verify(mockIpvSessionService).revokeAccessToken(ipvSessionItem);

        ArgumentCaptor<AuditEvent> auditEventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(mockAuditService).sendAuditEvent(auditEventCaptor.capture());

        AuditEvent capturedAuditEvent = auditEventCaptor.getValue();
        assertEquals(AuditEventTypes.IPV_IDENTITY_ISSUED, capturedAuditEvent.getEventName());
        AuditExtensionsUserIdentity extensions =
                (AuditExtensionsUserIdentity) capturedAuditEvent.getExtensions();
        assertEquals(Vot.P2, extensions.getLevelOfConfidence());
        assertFalse(extensions.isCiFail());
        assertTrue(extensions.isHasMitigations());
        assertEquals(3, responseBody.getReturnCode().size());
        verify(mockClientOAuthSessionDetailsService, times(1)).getClientOAuthSession(any());
        verify(mockCiMitService, times(1)).getContraIndicatorsVc(any(), any(), any());

        verify(mockConfigService).setFeatureSet(List.of("someCoolNewThing"));

        verify(mockUserIdentityService)
                .generateUserIdentity(
                        List.of(VC_ADDRESS), TEST_USER_ID, Vot.P2, mockContraIndicators);

        verify(mockSessionCredentialsService, times(1))
                .deleteSessionCredentials(TEST_IPV_SESSION_ID);
    }

    @Test
    void
            shouldReturnCredentialsWithCiMitVCOnSuccessfulUserInfoRequestAndHasMitigationsFalseAndOnlyNotFoundReturnCodeInCiConfigForAuditEventReturnCodes()
                    throws Exception {
        // Arrange
        when(mockConfigService.getContraIndicatorConfigMap())
                .thenReturn(
                        Map.of(
                                "X01", new ContraIndicatorConfig("X01", 4, -3, "4"),
                                "X02", new ContraIndicatorConfig("X02", 4, -3, "2"),
                                "Z03", new ContraIndicatorConfig("Z03", 4, -3, "3")));
        when(mockCiMitUtilityService.isBreachingCiThreshold(any())).thenReturn(false);
        ipvSessionItem.setVot(Vot.P0);
        when(mockIpvSessionService.getIpvSessionByAccessToken(TEST_ACCESS_TOKEN))
                .thenReturn(Optional.ofNullable(ipvSessionItem));
        when(mockUserIdentityService.generateUserIdentity(any(), any(), any(), any()))
                .thenReturn(userIdentity);
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockCiMitService.getContraIndicators(any())).thenReturn(CONTRA_INDICATORS);
        when(mockCiMitService.getContraIndicatorsVc(any(), any(), any()))
                .thenReturn(
                        VerifiableCredential.fromValidJwt(
                                TEST_USER_ID,
                                "test-cri-id",
                                SignedJWT.parse(SIGNED_CONTRA_INDICATOR_VC)));
        // Act
        APIGatewayProxyResponseEvent response =
                buildUserIdentityHandler.handleRequest(testEvent, mockContext);

        // Assert
        assertEquals(200, response.getStatusCode());

        UserIdentity responseBody =
                OBJECT_MAPPER.readValue(response.getBody(), new TypeReference<>() {});

        assertEquals(4, responseBody.getVcs().size());
        assertEquals(userIdentity.getVcs(), responseBody.getVcs());
        assertEquals(userIdentity.getIdentityClaim(), responseBody.getIdentityClaim());
        assertEquals(userIdentity.getAddressClaim(), responseBody.getAddressClaim());
        assertEquals(userIdentity.getDrivingPermitClaim(), responseBody.getDrivingPermitClaim());
        assertEquals(userIdentity.getNinoClaim(), responseBody.getNinoClaim());
        assertEquals(userIdentity.getReturnCode().size(), responseBody.getReturnCode().size());

        verify(mockIpvSessionService).revokeAccessToken(ipvSessionItem);

        ArgumentCaptor<AuditEvent> auditEventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(mockAuditService).sendAuditEvent(auditEventCaptor.capture());

        AuditEvent capturedAuditEvent = auditEventCaptor.getValue();
        assertEquals(AuditEventTypes.IPV_IDENTITY_ISSUED, capturedAuditEvent.getEventName());
        var expectedExtension =
                new AuditExtensionsUserIdentity(
                        Vot.P0,
                        false,
                        false,
                        List.of(
                                new AuditEventReturnCode("1", List.of()),
                                new AuditEventReturnCode(
                                        "2",
                                        List.of(
                                                "https://review-q.account.gov.uk",
                                                "https://review-f.account.gov.uk")),
                                new AuditEventReturnCode(
                                        "3",
                                        List.of(
                                                "https://review-z.account.gov.uk",
                                                "https://review-f.account.gov.uk"))));
        assertEquals(expectedExtension, capturedAuditEvent.getExtensions());

        verify(mockClientOAuthSessionDetailsService, times(1)).getClientOAuthSession(any());
        verify(mockCiMitService, times(1)).getContraIndicatorsVc(any(), any(), any());

        verify(mockConfigService).setFeatureSet(List.of("someCoolNewThing"));
        verify(mockSessionCredentialsService, times(1))
                .deleteSessionCredentials(TEST_IPV_SESSION_ID);
    }

    @Test
    void shouldReturnRiskAssessmentCredentialsWhenTicfIsEnabled() throws Exception {
        // Arrange
        when(mockConfigService.getContraIndicatorConfigMap())
                .thenReturn(
                        Map.of(
                                "X01", new ContraIndicatorConfig("X01", 4, -3, "4"),
                                "X02", new ContraIndicatorConfig("X02", 4, -3, "2"),
                                "Z03", new ContraIndicatorConfig("Z03", 4, -3, "3")));
        when(mockCiMitUtilityService.isBreachingCiThreshold(any())).thenReturn(false);
        ipvSessionItem.setVot(Vot.P0);
        ipvSessionItem.setRiskAssessmentCredential(vcTicf().getVcString());
        when(mockIpvSessionService.getIpvSessionByAccessToken(TEST_ACCESS_TOKEN))
                .thenReturn(Optional.ofNullable(ipvSessionItem));
        when(mockUserIdentityService.generateUserIdentity(any(), any(), any(), any()))
                .thenReturn(userIdentity);
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockCiMitService.getContraIndicators(any())).thenReturn(CONTRA_INDICATORS);
        when(mockCiMitService.getContraIndicatorsVc(any(), any(), any()))
                .thenReturn(
                        VerifiableCredential.fromValidJwt(
                                TEST_USER_ID,
                                "test-cri-id",
                                SignedJWT.parse(SIGNED_CONTRA_INDICATOR_VC)));
        when(mockConfigService.enabled(TICF_CRI_BETA)).thenReturn(true);
        // Act
        APIGatewayProxyResponseEvent response =
                buildUserIdentityHandler.handleRequest(testEvent, mockContext);

        // Assert
        assertEquals(200, response.getStatusCode());

        UserIdentity responseBody =
                OBJECT_MAPPER.readValue(response.getBody(), new TypeReference<>() {});

        assertEquals(5, responseBody.getVcs().size());
        assertEquals(userIdentity.getVcs(), responseBody.getVcs());
        assertEquals(userIdentity.getIdentityClaim(), responseBody.getIdentityClaim());
        assertEquals(userIdentity.getAddressClaim(), responseBody.getAddressClaim());
        assertEquals(userIdentity.getDrivingPermitClaim(), responseBody.getDrivingPermitClaim());
        assertEquals(userIdentity.getNinoClaim(), responseBody.getNinoClaim());
        assertEquals(userIdentity.getReturnCode().size(), responseBody.getReturnCode().size());
        verify(mockSessionCredentialsService, times(1))
                .deleteSessionCredentials(TEST_IPV_SESSION_ID);
    }

    @Test
    void
            shouldReturnCredentialsWithCiMitVCOnSuccessfulUserInfoRequestAndHasMitigationsFalseCiConfigForAuditEventReturnCodesAndCheckedDuplicateIssuers()
                    throws Exception {
        ContraIndicators contraIndicators =
                ContraIndicators.builder()
                        .usersContraIndicators(
                                List.of(
                                        ContraIndicator.builder()
                                                .code("X01")
                                                .issuers(
                                                        List.of(
                                                                "https://review-d.account.gov.uk",
                                                                "https://review-f.account.gov.uk"))
                                                .build(),
                                        ContraIndicator.builder()
                                                .code("X02")
                                                .issuers(
                                                        List.of(
                                                                "https://review-d.account.gov.uk",
                                                                "https://review-f.account.gov.uk"))
                                                .build(),
                                        ContraIndicator.builder()
                                                .code("Z03")
                                                .issuers(
                                                        List.of(
                                                                "https://review-w.account.gov.uk",
                                                                "https://review-x.account.gov.uk"))
                                                .build()))
                        .build();

        // Arrange
        when(mockConfigService.getContraIndicatorConfigMap())
                .thenReturn(
                        Map.of(
                                "X01", new ContraIndicatorConfig("X01", 4, -3, "1"),
                                "X02", new ContraIndicatorConfig("X02", 4, -3, "1"),
                                "Z03", new ContraIndicatorConfig("Z03", 4, -3, "3")));
        when(mockCiMitUtilityService.isBreachingCiThreshold(any())).thenReturn(false);
        ipvSessionItem.setVot(Vot.P0);
        when(mockIpvSessionService.getIpvSessionByAccessToken(TEST_ACCESS_TOKEN))
                .thenReturn(Optional.ofNullable(ipvSessionItem));
        when(mockUserIdentityService.generateUserIdentity(any(), any(), any(), any()))
                .thenReturn(userIdentity);
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockCiMitService.getContraIndicators(any())).thenReturn(contraIndicators);
        when(mockCiMitService.getContraIndicatorsVc(any(), any(), any()))
                .thenReturn(
                        VerifiableCredential.fromValidJwt(
                                TEST_USER_ID,
                                "test-cri-id",
                                SignedJWT.parse(SIGNED_CONTRA_INDICATOR_VC)));
        when(mockConfigService.enabled(TICF_CRI_BETA)).thenReturn(true);
        // Act
        APIGatewayProxyResponseEvent response =
                buildUserIdentityHandler.handleRequest(testEvent, mockContext);

        // Assert
        assertEquals(200, response.getStatusCode());

        UserIdentity responseBody =
                OBJECT_MAPPER.readValue(response.getBody(), new TypeReference<>() {});

        assertEquals(4, responseBody.getVcs().size());
        assertEquals(userIdentity.getVcs(), responseBody.getVcs());
        assertEquals(userIdentity.getIdentityClaim(), responseBody.getIdentityClaim());
        assertEquals(userIdentity.getAddressClaim(), responseBody.getAddressClaim());
        assertEquals(userIdentity.getDrivingPermitClaim(), responseBody.getDrivingPermitClaim());
        assertEquals(userIdentity.getNinoClaim(), responseBody.getNinoClaim());
        assertEquals(userIdentity.getReturnCode().size(), responseBody.getReturnCode().size());

        verify(mockIpvSessionService).revokeAccessToken(ipvSessionItem);

        ArgumentCaptor<AuditEvent> auditEventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(mockAuditService).sendAuditEvent(auditEventCaptor.capture());

        AuditEvent capturedAuditEvent = auditEventCaptor.getValue();
        assertEquals(AuditEventTypes.IPV_IDENTITY_ISSUED, capturedAuditEvent.getEventName());
        var expectedExtension =
                new AuditExtensionsUserIdentity(
                        Vot.P0,
                        false,
                        false,
                        List.of(
                                new AuditEventReturnCode(
                                        "1",
                                        List.of(
                                                "https://review-d.account.gov.uk",
                                                "https://review-f.account.gov.uk")),
                                new AuditEventReturnCode("2", List.of()),
                                new AuditEventReturnCode(
                                        "3",
                                        List.of(
                                                "https://review-w.account.gov.uk",
                                                "https://review-x.account.gov.uk"))));
        assertEquals(expectedExtension, capturedAuditEvent.getExtensions());

        verify(mockClientOAuthSessionDetailsService, times(1)).getClientOAuthSession(any());
        verify(mockCiMitService, times(1)).getContraIndicatorsVc(any(), any(), any());

        verify(mockConfigService).setFeatureSet(List.of("someCoolNewThing"));
        verify(mockSessionCredentialsService, times(1))
                .deleteSessionCredentials(TEST_IPV_SESSION_ID);
    }

    @Test
    void shouldReturnErrorResponseWhenUserIdentityGenerationFails() throws Exception {
        when(mockIpvSessionService.getIpvSessionByAccessToken(TEST_ACCESS_TOKEN))
                .thenReturn(Optional.ofNullable(ipvSessionItem));
        when(mockUserIdentityService.generateUserIdentity(any(), any(), any(), any()))
                .thenThrow(
                        new HttpResponseExceptionWithErrorBody(
                                500, ErrorResponse.FAILED_TO_GENERATE_IDENTIY_CLAIM));
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);

        APIGatewayProxyResponseEvent response =
                buildUserIdentityHandler.handleRequest(testEvent, mockContext);

        responseBody = OBJECT_MAPPER.readValue(response.getBody(), new TypeReference<>() {});

        assertEquals(500, response.getStatusCode());
        assertEquals(
                valueOf(ErrorResponse.FAILED_TO_GENERATE_IDENTIY_CLAIM.getCode()),
                responseBody.get("error"));
        assertEquals(
                ErrorResponse.FAILED_TO_GENERATE_IDENTIY_CLAIM.getMessage(),
                responseBody.get("error_description"));
        verify(mockClientOAuthSessionDetailsService, times(1)).getClientOAuthSession(any());
        verify(mockSessionCredentialsService, never()).deleteSessionCredentials(any());
    }

    @Test
    void shouldReturnErrorResponseOnCIRetrievalException() throws Exception {
        when(mockIpvSessionService.getIpvSessionByAccessToken(TEST_ACCESS_TOKEN))
                .thenReturn(Optional.ofNullable(ipvSessionItem));
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockCiMitService.getContraIndicatorsVc(any(), any(), any()))
                .thenThrow(new CiRetrievalException("Lambda execution failed"));

        APIGatewayProxyResponseEvent response =
                buildUserIdentityHandler.handleRequest(testEvent, mockContext);
        responseBody = OBJECT_MAPPER.readValue(response.getBody(), new TypeReference<>() {});

        assertEquals(500, response.getStatusCode());
        assertEquals("server_error", responseBody.get("error"));
        assertEquals(
                "Unexpected server error - Error when fetching CIs from storage system. Lambda execution failed",
                responseBody.get("error_description"));
        verify(mockClientOAuthSessionDetailsService, times(1)).getClientOAuthSession(any());
        verify(mockCiMitService, times(1)).getContraIndicatorsVc(any(), any(), any());
        verify(mockSessionCredentialsService, never()).deleteSessionCredentials(any());
    }

    @Test
    void shouldReturnErrorResponseWhenTokenIsNull()
            throws JsonProcessingException, VerifiableCredentialException {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>(Collections.emptyMap());
        headers.put("Authorization", null);
        headers.put("ip-address", TEST_IP_ADDRESS);
        event.setHeaders(headers);

        APIGatewayProxyResponseEvent response =
                buildUserIdentityHandler.handleRequest(event, mockContext);
        responseBody = OBJECT_MAPPER.readValue(response.getBody(), new TypeReference<>() {});

        assertEquals(BearerTokenError.MISSING_TOKEN.getHTTPStatusCode(), response.getStatusCode());
        assertEquals(BearerTokenError.MISSING_TOKEN.getCode(), responseBody.get("error"));
        assertEquals(
                BearerTokenError.MISSING_TOKEN.getDescription(),
                responseBody.get("error_description"));
        verify(mockClientOAuthSessionDetailsService, times(0)).getClientOAuthSession(any());
        verify(mockSessionCredentialsService, never()).deleteSessionCredentials(any());
    }

    @Test
    void shouldReturnErrorResponseWhenTokenIsMissingBearerPrefix()
            throws JsonProcessingException, VerifiableCredentialException {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        Map<String, String> headers =
                Map.of("Authorization", "11111111", "ip-address", TEST_IP_ADDRESS);
        event.setHeaders(headers);

        APIGatewayProxyResponseEvent response =
                buildUserIdentityHandler.handleRequest(event, mockContext);
        responseBody = OBJECT_MAPPER.readValue(response.getBody(), new TypeReference<>() {});

        assertEquals(
                BearerTokenError.INVALID_REQUEST.getHTTPStatusCode(), response.getStatusCode());
        assertEquals(BearerTokenError.INVALID_REQUEST.getCode(), responseBody.get("error"));
        assertEquals(
                BearerTokenError.INVALID_REQUEST.getDescription(),
                responseBody.get("error_description"));
        verify(mockClientOAuthSessionDetailsService, times(0)).getClientOAuthSession(any());
        verify(mockSessionCredentialsService, never()).deleteSessionCredentials(any());
    }

    @Test
    void shouldReturnErrorResponseWhenTokenIsMissing()
            throws JsonProcessingException, VerifiableCredentialException {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = Map.of("ip-address", TEST_IP_ADDRESS);
        event.setHeaders(headers);

        APIGatewayProxyResponseEvent response =
                buildUserIdentityHandler.handleRequest(event, mockContext);
        responseBody = OBJECT_MAPPER.readValue(response.getBody(), new TypeReference<>() {});

        assertEquals(BearerTokenError.MISSING_TOKEN.getHTTPStatusCode(), response.getStatusCode());
        assertEquals(BearerTokenError.MISSING_TOKEN.getCode(), responseBody.get("error"));
        assertEquals(
                BearerTokenError.MISSING_TOKEN.getDescription(),
                responseBody.get("error_description"));
        verify(mockClientOAuthSessionDetailsService, times(0)).getClientOAuthSession(any());
        verify(mockSessionCredentialsService, never()).deleteSessionCredentials(any());
    }

    @Test
    void shouldReturnErrorResponseWhenInvalidAccessTokenProvided()
            throws JsonProcessingException, VerifiableCredentialException {
        when(mockIpvSessionService.getIpvSessionByAccessToken(TEST_ACCESS_TOKEN))
                .thenReturn(Optional.empty());

        APIGatewayProxyResponseEvent response =
                buildUserIdentityHandler.handleRequest(testEvent, mockContext);
        Map<String, Object> responseBody =
                OBJECT_MAPPER.readValue(response.getBody(), new TypeReference<>() {});

        assertEquals(403, response.getStatusCode());
        assertEquals(OAuth2Error.ACCESS_DENIED.getCode(), responseBody.get("error"));
        assertEquals(
                OAuth2Error.ACCESS_DENIED
                        .appendDescription(
                                " - The supplied access token was not found in the database")
                        .getDescription(),
                responseBody.get("error_description"));
        verify(mockClientOAuthSessionDetailsService, times(0)).getClientOAuthSession(any());
        verify(mockSessionCredentialsService, never()).deleteSessionCredentials(any());
    }

    @Test
    void shouldReturnErrorResponseWhenAccessTokenHasBeenRevoked()
            throws JsonProcessingException, VerifiableCredentialException {
        AccessTokenMetadata revokedAccessTokenMetadata = new AccessTokenMetadata();
        revokedAccessTokenMetadata.setRevokedAtDateTime(Instant.now().toString());
        ipvSessionItem.setAccessTokenMetadata(revokedAccessTokenMetadata);
        when(mockIpvSessionService.getIpvSessionByAccessToken(TEST_ACCESS_TOKEN))
                .thenReturn(Optional.ofNullable(ipvSessionItem));

        APIGatewayProxyResponseEvent response =
                buildUserIdentityHandler.handleRequest(testEvent, mockContext);
        Map<String, Object> responseBody =
                OBJECT_MAPPER.readValue(response.getBody(), new TypeReference<>() {});

        assertEquals(403, response.getStatusCode());
        assertEquals(OAuth2Error.ACCESS_DENIED.getCode(), responseBody.get("error"));
        assertEquals(
                OAuth2Error.ACCESS_DENIED
                        .appendDescription(" - The supplied access token has been revoked")
                        .getDescription(),
                responseBody.get("error_description"));
        verify(mockClientOAuthSessionDetailsService, times(0)).getClientOAuthSession(any());
        verify(mockSessionCredentialsService, never()).deleteSessionCredentials(any());
    }

    @Test
    void shouldReturn403ErrorResponseWhenAccessTokenHasExpired()
            throws JsonProcessingException, VerifiableCredentialException {
        AccessTokenMetadata expiredAccessTokenMetadata = new AccessTokenMetadata();
        expiredAccessTokenMetadata.setExpiryDateTime(Instant.now().minusSeconds(5).toString());
        ipvSessionItem.setAccessTokenMetadata(expiredAccessTokenMetadata);
        when(mockIpvSessionService.getIpvSessionByAccessToken(TEST_ACCESS_TOKEN))
                .thenReturn(Optional.ofNullable(ipvSessionItem));

        APIGatewayProxyResponseEvent response =
                buildUserIdentityHandler.handleRequest(testEvent, mockContext);
        Map<String, Object> responseBody =
                OBJECT_MAPPER.readValue(response.getBody(), new TypeReference<>() {});

        assertEquals(403, response.getStatusCode());
        assertEquals(OAuth2Error.ACCESS_DENIED.getCode(), responseBody.get("error"));
        assertEquals(
                OAuth2Error.ACCESS_DENIED
                        .appendDescription(" - The supplied access token has expired")
                        .getDescription(),
                responseBody.get("error_description"));
        verify(mockClientOAuthSessionDetailsService, times(0)).getClientOAuthSession(any());
        verify(mockSessionCredentialsService, never()).deleteSessionCredentials(any());
    }

    @Test
    void shouldReturnErrorResponseOnCredentialParseException() throws Exception {
        when(mockIpvSessionService.getIpvSessionByAccessToken(TEST_ACCESS_TOKEN))
                .thenReturn(Optional.ofNullable(ipvSessionItem));
        when(mockUserIdentityService.generateUserIdentity(any(), any(), any(), any()))
                .thenThrow(
                        new CredentialParseException(
                                "Encountered a parsing error while attempting to purchase successful VC Store items."));
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);

        APIGatewayProxyResponseEvent response =
                buildUserIdentityHandler.handleRequest(testEvent, mockContext);
        responseBody = OBJECT_MAPPER.readValue(response.getBody(), new TypeReference<>() {});

        assertEquals(500, response.getStatusCode());
        assertEquals("server_error", responseBody.get("error"));
        assertEquals(
                "Unexpected server error - Failed to parse successful VC Store items. Encountered a parsing error while attempting to purchase successful VC Store items.",
                responseBody.get("error_description"));
        verify(mockClientOAuthSessionDetailsService, times(1)).getClientOAuthSession(any());
        verify(mockUserIdentityService, times(1)).generateUserIdentity(any(), any(), any(), any());
        verify(mockSessionCredentialsService, never()).deleteSessionCredentials(any());
    }

    @Test
    void shouldReturnErrorResponseOnUnrecognisedCiException() throws Exception {
        when(mockIpvSessionService.getIpvSessionByAccessToken(TEST_ACCESS_TOKEN))
                .thenReturn(Optional.ofNullable(ipvSessionItem));
        when(mockUserIdentityService.generateUserIdentity(any(), any(), any(), any()))
                .thenThrow(new UnrecognisedCiException("This shouldn't really happen"));
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);

        APIGatewayProxyResponseEvent response =
                buildUserIdentityHandler.handleRequest(testEvent, mockContext);
        responseBody = OBJECT_MAPPER.readValue(response.getBody(), new TypeReference<>() {});

        assertEquals(500, response.getStatusCode());
        assertEquals("server_error", responseBody.get("error"));
        assertEquals(
                "Unexpected server error - CI error. This shouldn't really happen",
                responseBody.get("error_description"));
        verify(mockClientOAuthSessionDetailsService, times(1)).getClientOAuthSession(any());
        verify(mockUserIdentityService, times(1)).generateUserIdentity(any(), any(), any(), any());
        verify(mockSessionCredentialsService, never()).deleteSessionCredentials(any());
    }

    @Test
    void shouldReturnErrorResponseOnSessionCredentialsReadFailure() throws Exception {
        when(mockIpvSessionService.getIpvSessionByAccessToken(TEST_ACCESS_TOKEN))
                .thenReturn(Optional.ofNullable(ipvSessionItem));
        when(mockSessionCredentialsService.getCredentials(TEST_IPV_SESSION_ID, TEST_USER_ID))
                .thenThrow(new VerifiableCredentialException(418, FAILED_TO_GET_CREDENTIAL));
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);

        APIGatewayProxyResponseEvent response =
                buildUserIdentityHandler.handleRequest(testEvent, mockContext);
        responseBody = OBJECT_MAPPER.readValue(response.getBody(), new TypeReference<>() {});

        assertEquals(418, response.getStatusCode());
        assertEquals(valueOf(FAILED_TO_GET_CREDENTIAL.getCode()), responseBody.get("error"));
        assertEquals(FAILED_TO_GET_CREDENTIAL.getMessage(), responseBody.get("error_description"));
        verify(mockUserIdentityService, never()).generateUserIdentity(any(), any(), any(), any());
        verify(mockSessionCredentialsService, never()).deleteSessionCredentials(any());
    }

    private static APIGatewayProxyRequestEvent getEventWithAuthAndIpHeaders() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        AccessToken accessToken = new BearerAccessToken(TEST_ACCESS_TOKEN);
        Map<String, String> headers =
                Map.of(
                        "Authorization",
                        accessToken.toAuthorizationHeader(),
                        "ip-address",
                        TEST_IP_ADDRESS);
        event.setHeaders(headers);
        return event;
    }
}
