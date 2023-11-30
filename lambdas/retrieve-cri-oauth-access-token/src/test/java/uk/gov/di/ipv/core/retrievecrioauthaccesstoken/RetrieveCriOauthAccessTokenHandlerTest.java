package uk.gov.di.ipv.core.retrievecrioauthaccesstoken;

import com.amazonaws.services.lambda.runtime.Context;
import com.nimbusds.oauth2.sdk.OAuth2Error;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import net.minidev.json.JSONObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.di.ipv.core.library.auditing.AuditEvent;
import uk.gov.di.ipv.core.library.auditing.AuditEventTypes;
import uk.gov.di.ipv.core.library.auditing.AuditEventUser;
import uk.gov.di.ipv.core.library.domain.ErrorResponse;
import uk.gov.di.ipv.core.library.dto.CredentialIssuerConfig;
import uk.gov.di.ipv.core.library.exceptions.JourneyError;
import uk.gov.di.ipv.core.library.exceptions.SqsException;
import uk.gov.di.ipv.core.library.helpers.SecureTokenHelper;
import uk.gov.di.ipv.core.library.kmses256signer.KmsEs256Signer;
import uk.gov.di.ipv.core.library.persistence.item.ClientOAuthSessionItem;
import uk.gov.di.ipv.core.library.persistence.item.CriOAuthSessionItem;
import uk.gov.di.ipv.core.library.persistence.item.IpvSessionItem;
import uk.gov.di.ipv.core.library.service.AuditService;
import uk.gov.di.ipv.core.library.service.ClientOAuthSessionDetailsService;
import uk.gov.di.ipv.core.library.service.ConfigService;
import uk.gov.di.ipv.core.library.service.CriOAuthSessionService;
import uk.gov.di.ipv.core.library.service.IpvSessionService;
import uk.gov.di.ipv.core.retrievecrioauthaccesstoken.exception.AuthCodeToAccessTokenException;
import uk.gov.di.ipv.core.retrievecrioauthaccesstoken.service.AuthCodeToAccessTokenService;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.COMPONENT_ID;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.RSA_ENCRYPTION_PUBLIC_JWK;

@ExtendWith(MockitoExtension.class)
class RetrieveCriOauthAccessTokenHandlerTest {

    private static final String OAUTH_STATE = "oauth-state";
    private static final String CREDENTIAL_ISSUER_ID = "PassportIssuer";
    private static final String IPV_SESSION_ID = "ipvSessionId";
    private static final String TEST_USER_ID = "test-user-id";
    private static final String TEST_AUTH_CODE = "test-auth-code";

    @Mock private Context context;
    @Mock private KmsEs256Signer signer;
    @Mock private AuthCodeToAccessTokenService authCodeToAccessTokenService;
    @Mock private AuditService auditService;
    @Mock private static ConfigService configService;
    @Mock private IpvSessionService ipvSessionService;
    @Mock private IpvSessionItem ipvSessionItem;
    @Mock private CriOAuthSessionService criOAuthSessionService;
    @Mock private ClientOAuthSessionDetailsService mockClientOAuthSessionService;
    @InjectMocks private RetrieveCriOauthAccessTokenHandler handler;

    private static CredentialIssuerConfig passportIssuer;

    private static final String sessionId = SecureTokenHelper.generate();
    private static final String passportIssuerId = CREDENTIAL_ISSUER_ID;
    private static final String testApiKey = "test-api-key";
    private static final String testComponentId = "http://ipv-core-test.example.com";
    private static CriOAuthSessionItem criOAuthSessionItem;

    @BeforeAll
    static void setUp() throws URISyntaxException {

        passportIssuer =
                new CredentialIssuerConfig(
                        new URI("http://www.example.com"),
                        new URI("http://www.example.com/credential"),
                        new URI("http://www.example.com/authorize"),
                        "ipv-core",
                        "{}",
                        RSA_ENCRYPTION_PUBLIC_JWK,
                        "test-audience",
                        new URI("http://www.example.com/credential-issuers/callback/criId"),
                        true,
                        false);

        criOAuthSessionItem =
                CriOAuthSessionItem.builder()
                        .criOAuthSessionId(OAUTH_STATE)
                        .criId(CREDENTIAL_ISSUER_ID)
                        .accessToken("testAccessToken")
                        .authorizationCode(TEST_AUTH_CODE)
                        .build();
    }

    @Test
    void shouldReceiveSuccessResponseOnSuccessfulRequest() throws SqsException {
        Map<String, String> input = Map.of(IPV_SESSION_ID, sessionId);

        JSONObject testCredential = new JSONObject();
        testCredential.appendField("foo", "bar");

        when(authCodeToAccessTokenService.exchangeCodeForToken(
                        TEST_AUTH_CODE, passportIssuer, testApiKey, CREDENTIAL_ISSUER_ID))
                .thenReturn(new BearerAccessToken());

        mockServiceCallsAndSessionItem();

        Map<String, Object> output = handler.handleRequest(input, context);

        ArgumentCaptor<AuditEvent> auditEventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService, times(1)).sendAuditEvent(auditEventCaptor.capture());
        var auditEvents = auditEventCaptor.getAllValues();
        assertEquals(
                AuditEventTypes.IPV_CRI_ACCESS_TOKEN_EXCHANGED, auditEvents.get(0).getEventName());

        ArgumentCaptor<CriOAuthSessionItem> criOAuthSessionServiceCaptor =
                ArgumentCaptor.forClass(CriOAuthSessionItem.class);
        verify(criOAuthSessionService)
                .updateCriOAuthSessionItem(criOAuthSessionServiceCaptor.capture());
        assertEquals(
                criOAuthSessionItem.getAccessToken(),
                criOAuthSessionServiceCaptor.getValue().getAccessToken());

        assertEquals("success", output.get("result"));
        verify(criOAuthSessionService, times(1)).getCriOauthSessionItem(any());
        verify(criOAuthSessionService, times(1)).updateCriOAuthSessionItem(any());
    }

    @Test
    void shouldThrowJourneyErrorIfCredentialIssuerServiceThrowsException() {
        mockServiceCallsAndSessionItem();
        when(authCodeToAccessTokenService.exchangeCodeForToken(
                        TEST_AUTH_CODE, passportIssuer, testApiKey, CREDENTIAL_ISSUER_ID))
                .thenThrow(
                        new AuthCodeToAccessTokenException(
                                HTTPResponse.SC_BAD_REQUEST, ErrorResponse.INVALID_TOKEN_REQUEST));

        Map<String, String> input = Map.of(IPV_SESSION_ID, sessionId);
        assertThrows(JourneyError.class, () -> handler.handleRequest(input, context));
    }

    @Test
    void shouldSendIpvVcReceivedAuditEvent() throws Exception {
        BearerAccessToken accessToken = mock(BearerAccessToken.class);

        when(authCodeToAccessTokenService.exchangeCodeForToken(
                        TEST_AUTH_CODE, passportIssuer, testApiKey, CREDENTIAL_ISSUER_ID))
                .thenReturn(accessToken);

        mockServiceCallsAndSessionItem();

        Map<String, String> input = Map.of(IPV_SESSION_ID, sessionId);

        handler.handleRequest(input, context);

        ArgumentCaptor<AuditEvent> auditEventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService, times(1)).sendAuditEvent(auditEventCaptor.capture());
        var auditEvents = auditEventCaptor.getAllValues();
        assertEquals(
                AuditEventTypes.IPV_CRI_ACCESS_TOKEN_EXCHANGED, auditEvents.get(0).getEventName());

        assertEquals(testComponentId, auditEvents.get(0).getComponentId());
        AuditEventUser auditEventUser = auditEvents.get(0).getUser();
        assertEquals(TEST_USER_ID, auditEventUser.getUserId());
        assertEquals(sessionId, auditEventUser.getSessionId());

        verify(criOAuthSessionService, times(1)).getCriOauthSessionItem(any());
        verify(criOAuthSessionService, times(1)).updateCriOAuthSessionItem(any());
    }

    private void mockServiceCallsAndSessionItem() {
        when(configService.getCriConfig(criOAuthSessionItem)).thenReturn(passportIssuer);
        when(configService.getSsmParameter(COMPONENT_ID)).thenReturn(testComponentId);
        when(configService.getCriPrivateApiKey(criOAuthSessionItem)).thenReturn(testApiKey);
        when(ipvSessionService.getIpvSession(anyString())).thenReturn(ipvSessionItem);
        when(criOAuthSessionService.getCriOauthSessionItem(any())).thenReturn(criOAuthSessionItem);
        when(mockClientOAuthSessionService.getClientOAuthSession(any()))
                .thenReturn(getClientOAuthSessionItem());
    }

    @Test
    void shouldThrowJourneyErrorIfSqsExceptionIsThrown() throws SqsException {
        mockServiceCallsAndSessionItem();
        doThrow(new SqsException("Test sqs error"))
                .when(auditService)
                .sendAuditEvent(any(AuditEvent.class));

        Map<String, String> input = Map.of(IPV_SESSION_ID, sessionId);
        assertThrows(JourneyError.class, () -> handler.handleRequest(input, context));
        verify(criOAuthSessionService, times(1)).getCriOauthSessionItem(any());
    }

    @Test
    void shouldUpdateSessionWithDetailsOfFailedCriVisitOnCredentialIssuerException() {
        mockServiceCallsAndSessionItem();
        when(authCodeToAccessTokenService.exchangeCodeForToken(
                        TEST_AUTH_CODE, passportIssuer, testApiKey, CREDENTIAL_ISSUER_ID))
                .thenThrow(
                        new AuthCodeToAccessTokenException(
                                HTTPResponse.SC_BAD_REQUEST, ErrorResponse.INVALID_TOKEN_REQUEST));

        IpvSessionItem ipvSessionItem = new IpvSessionItem();
        when(ipvSessionService.getIpvSession(anyString())).thenReturn(ipvSessionItem);

        Map<String, String> input = Map.of(IPV_SESSION_ID, sessionId);
        assertThrows(JourneyError.class, () -> handler.handleRequest(input, context));

        ArgumentCaptor<IpvSessionItem> ipvSessionItemArgumentCaptor =
                ArgumentCaptor.forClass(IpvSessionItem.class);
        verify(ipvSessionService).updateIpvSession(ipvSessionItemArgumentCaptor.capture());
        var updatedIpvSessionItem = ipvSessionItemArgumentCaptor.getValue();
        assertEquals(1, updatedIpvSessionItem.getVisitedCredentialIssuerDetails().size());
        assertEquals(
                passportIssuerId,
                updatedIpvSessionItem.getVisitedCredentialIssuerDetails().get(0).getCriId());
        assertFalse(
                updatedIpvSessionItem
                        .getVisitedCredentialIssuerDetails()
                        .get(0)
                        .isReturnedWithVc());
        assertEquals(
                OAuth2Error.SERVER_ERROR_CODE,
                updatedIpvSessionItem.getVisitedCredentialIssuerDetails().get(0).getOauthError());
        verify(criOAuthSessionService, times(1)).getCriOauthSessionItem(any());
    }

    @Test
    void shouldUpdateSessionWithDetailsOfFailedVisitedCriOnSqsException() throws SqsException {
        mockServiceCallsAndSessionItem();
        IpvSessionItem ipvSessionItem = new IpvSessionItem();
        when(ipvSessionService.getIpvSession(anyString())).thenReturn(ipvSessionItem);
        doThrow(new SqsException("Test sqs error"))
                .when(auditService)
                .sendAuditEvent(any(AuditEvent.class));

        Map<String, String> input = Map.of(IPV_SESSION_ID, sessionId);
        assertThrows(JourneyError.class, () -> handler.handleRequest(input, context));

        ArgumentCaptor<IpvSessionItem> ipvSessionItemArgumentCaptor =
                ArgumentCaptor.forClass(IpvSessionItem.class);
        verify(ipvSessionService).updateIpvSession(ipvSessionItemArgumentCaptor.capture());
        var updatedIpvSessionItem = ipvSessionItemArgumentCaptor.getValue();
        assertEquals(1, updatedIpvSessionItem.getVisitedCredentialIssuerDetails().size());
        assertEquals(
                passportIssuerId,
                updatedIpvSessionItem.getVisitedCredentialIssuerDetails().get(0).getCriId());
        assertFalse(
                updatedIpvSessionItem
                        .getVisitedCredentialIssuerDetails()
                        .get(0)
                        .isReturnedWithVc());
        assertEquals(
                OAuth2Error.SERVER_ERROR_CODE,
                updatedIpvSessionItem.getVisitedCredentialIssuerDetails().get(0).getOauthError());
        verify(criOAuthSessionService, times(1)).getCriOauthSessionItem(any());
    }

    @Test
    void shouldPassNullApiKeyWhenCriDoesNotRequireApiKey() throws URISyntaxException {
        Map<String, String> input = Map.of(IPV_SESSION_ID, sessionId);
        CredentialIssuerConfig testCriNotRequiringApiKey =
                new CredentialIssuerConfig(
                        new URI("http://www.example.com"),
                        new URI("http://www.example.com/credential"),
                        new URI("http://www.example.com/authorize"),
                        "ipv-core",
                        "{}",
                        RSA_ENCRYPTION_PUBLIC_JWK,
                        "test-audience",
                        new URI("http://www.example.com/credential-issuers/callback/criId"),
                        false,
                        false);
        when(configService.getCriConfig(criOAuthSessionItem)).thenReturn(testCriNotRequiringApiKey);
        when(configService.getSsmParameter(COMPONENT_ID)).thenReturn(testComponentId);
        when(ipvSessionService.getIpvSession(anyString())).thenReturn(ipvSessionItem);
        when(criOAuthSessionService.getCriOauthSessionItem(any())).thenReturn(criOAuthSessionItem);
        when(mockClientOAuthSessionService.getClientOAuthSession(any()))
                .thenReturn(getClientOAuthSessionItem());

        when(authCodeToAccessTokenService.exchangeCodeForToken(any(), any(), any(), any()))
                .thenReturn(new BearerAccessToken());

        Map<String, Object> output = handler.handleRequest(input, context);

        verify(authCodeToAccessTokenService)
                .exchangeCodeForToken(TEST_AUTH_CODE, passportIssuer, null, CREDENTIAL_ISSUER_ID);
        assertEquals("success", output.get("result"));
    }

    private ClientOAuthSessionItem getClientOAuthSessionItem() {
        return ClientOAuthSessionItem.builder()
                .clientOAuthSessionId(SecureTokenHelper.generate())
                .responseType("code")
                .state("test-state")
                .redirectUri("https://example.com/redirect")
                .govukSigninJourneyId("test-journey-id")
                .userId("test-user-id")
                .build();
    }
}
