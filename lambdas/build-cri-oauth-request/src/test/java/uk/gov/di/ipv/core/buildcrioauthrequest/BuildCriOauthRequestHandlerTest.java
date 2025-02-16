package uk.gov.di.ipv.core.buildcrioauthrequest;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSADecrypter;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.SignedJWT;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URIBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.di.ipv.core.buildcrioauthrequest.domain.CriResponse;
import uk.gov.di.ipv.core.library.auditing.AuditEvent;
import uk.gov.di.ipv.core.library.auditing.AuditEventTypes;
import uk.gov.di.ipv.core.library.domain.Address;
import uk.gov.di.ipv.core.library.domain.ErrorResponse;
import uk.gov.di.ipv.core.library.domain.JourneyErrorResponse;
import uk.gov.di.ipv.core.library.domain.JourneyRequest;
import uk.gov.di.ipv.core.library.domain.VerifiableCredential;
import uk.gov.di.ipv.core.library.dto.OauthCriConfig;
import uk.gov.di.ipv.core.library.gpg45.Gpg45ProfileEvaluator;
import uk.gov.di.ipv.core.library.gpg45.Gpg45Scores;
import uk.gov.di.ipv.core.library.gpg45.exception.UnknownEvidenceTypeException;
import uk.gov.di.ipv.core.library.helpers.SecureTokenHelper;
import uk.gov.di.ipv.core.library.kmses256signer.KmsEs256SignerFactory;
import uk.gov.di.ipv.core.library.persistence.item.ClientOAuthSessionItem;
import uk.gov.di.ipv.core.library.persistence.item.IpvSessionItem;
import uk.gov.di.ipv.core.library.service.AuditService;
import uk.gov.di.ipv.core.library.service.ClientOAuthSessionDetailsService;
import uk.gov.di.ipv.core.library.service.ConfigService;
import uk.gov.di.ipv.core.library.service.CriOAuthSessionService;
import uk.gov.di.ipv.core.library.service.IpvSessionService;
import uk.gov.di.ipv.core.library.service.UserIdentityService;
import uk.gov.di.ipv.core.library.verifiablecredential.helpers.VcHelper;
import uk.gov.di.ipv.core.library.verifiablecredential.service.SessionCredentialsService;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.COMPONENT_ID;
import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.JWT_TTL_SECONDS;
import static uk.gov.di.ipv.core.library.domain.CriConstants.ADDRESS_CRI;
import static uk.gov.di.ipv.core.library.domain.CriConstants.CLAIMED_IDENTITY_CRI;
import static uk.gov.di.ipv.core.library.domain.CriConstants.DCMAW_CRI;
import static uk.gov.di.ipv.core.library.domain.CriConstants.F2F_CRI;
import static uk.gov.di.ipv.core.library.domain.CriConstants.HMRC_KBV_CRI;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.CREDENTIAL_ATTRIBUTES_1;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.CREDENTIAL_ATTRIBUTES_2;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.CREDENTIAL_ATTRIBUTES_3;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.CREDENTIAL_ATTRIBUTES_4;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.EC_PRIVATE_KEY;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.EC_PUBLIC_JWK;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.RSA_ENCRYPTION_PRIVATE_KEY;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.RSA_ENCRYPTION_PUBLIC_JWK;
import static uk.gov.di.ipv.core.library.helpers.VerifiableCredentialGenerator.generateVerifiableCredential;
import static uk.gov.di.ipv.core.library.helpers.VerifiableCredentialGenerator.vcClaim;

@ExtendWith(MockitoExtension.class)
class BuildCriOauthRequestHandlerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CRI_ID = "PassportIssuer";
    private static final String CRI_TOKEN_URL = "http://www.example.com";
    private static final String CRI_CREDENTIAL_URL = "http://www.example.com/credential";
    private static final String CRI_AUTHORIZE_URL = "http://www.example.com/authorize";
    private static final String IPV_ISSUER = "http://www.example.com/issuer";
    private static final String ADDRESS_ISSUER = "http://www.example.com/address/issuer";
    private static final String CRI_AUDIENCE = "http://www.example.com/audience";
    private static final String IPV_CLIENT_ID = "ipv-core";
    private static final String SESSION_ID = "the-session-id";
    private static final String TEST_USER_ID = "test-user-id";
    private static final String TEST_IP_ADDRESS = "192.168.1.100";
    private static final String TEST_SHARED_CLAIMS = "shared_claims";
    private static final String TEST_EVIDENCE_REQUESTED = "evidence_requested";
    private static final String JOURNEY_BASE_URL = "/journey/cri/build-oauth-request/%s";
    private static final String TEST_EMAIL_ADDRESS = "test@test.com";
    private static final String TEST_NI_NUMBER = "AA000003D";
    private static final String CONTEXT = "context";
    private static final String TEST_CONTEXT = "test_context";
    private static final String CRI_WITH_CONTEXT =
            String.format("%s?%s=%s", CLAIMED_IDENTITY_CRI, CONTEXT, TEST_CONTEXT);
    private static final String SCOPE = "scope";
    private static final String TEST_SCOPE = "test_scope";
    private static final String CRI_WITH_SCOPE =
            String.format("%s?%s=%s", CLAIMED_IDENTITY_CRI, SCOPE, TEST_SCOPE);
    private static final String CRI_WITH_CONTEXT_AND_SCOPE =
            String.format(
                    "%s?%s=%s&%s=%s",
                    CLAIMED_IDENTITY_CRI, CONTEXT, TEST_CONTEXT, SCOPE, TEST_SCOPE);

    private static final String TEST_CLIENT_OAUTH_SESSION_ID =
            SecureTokenHelper.getInstance().generate();
    public static final String MAIN_CONNECTION = "main";

    @Mock private Context context;
    @Mock private ConfigService configService;
    @Mock private UserIdentityService userIdentityService;
    @Mock private AuditService mockAuditService;
    @Mock private IpvSessionService mockIpvSessionService;
    @Mock private CriOAuthSessionService mockCriOAuthSessionService;
    @Mock private ClientOAuthSessionDetailsService mockClientOAuthSessionDetailsService;
    @Mock private Gpg45ProfileEvaluator mockGpg45ProfileEvaluator;
    @Mock private SessionCredentialsService mockSessionCredentialService;
    @Mock private MockedStatic<VcHelper> mockVcHelper;
    @Mock private KmsEs256SignerFactory mockKmsEs256SignerFactory;
    @InjectMocks private BuildCriOauthRequestHandler buildCriOauthRequestHandler;

    private OauthCriConfig oauthCriConfig;
    private OauthCriConfig addressOauthCriConfig;
    private OauthCriConfig dcmawOauthCriConfig;
    private OauthCriConfig f2FOauthCriConfig;
    private OauthCriConfig claimedIdentityOauthCriConfig;
    private OauthCriConfig hmrcKbvOauthCriConfig;
    private ClientOAuthSessionItem clientOAuthSessionItem;
    private final IpvSessionItem ipvSessionItem = new IpvSessionItem();

    @BeforeEach
    void setUp() throws URISyntaxException {
        oauthCriConfig =
                OauthCriConfig.builder()
                        .tokenUrl(new URI(CRI_TOKEN_URL))
                        .credentialUrl(new URI(CRI_CREDENTIAL_URL))
                        .authorizeUrl(new URI(CRI_AUTHORIZE_URL))
                        .clientId(IPV_CLIENT_ID)
                        .signingKey("{}")
                        .encryptionKey(RSA_ENCRYPTION_PUBLIC_JWK)
                        .componentId("http://www.example.com/audience")
                        .clientCallbackUrl(URI.create("http://www.example.com/callback/criId"))
                        .requiresApiKey(true)
                        .requiresAdditionalEvidence(false)
                        .build();

        addressOauthCriConfig =
                OauthCriConfig.builder()
                        .tokenUrl(new URI(CRI_TOKEN_URL))
                        .credentialUrl(new URI(CRI_CREDENTIAL_URL))
                        .authorizeUrl(new URI(CRI_AUTHORIZE_URL))
                        .clientId(IPV_CLIENT_ID)
                        .signingKey("{}")
                        .encryptionKey(RSA_ENCRYPTION_PUBLIC_JWK)
                        .componentId(ADDRESS_ISSUER)
                        .clientCallbackUrl(URI.create("http://www.example.com/callback/criId"))
                        .requiresApiKey(true)
                        .requiresAdditionalEvidence(false)
                        .build();

        dcmawOauthCriConfig =
                OauthCriConfig.builder()
                        .tokenUrl(new URI(CRI_TOKEN_URL))
                        .credentialUrl(new URI(CRI_CREDENTIAL_URL))
                        .authorizeUrl(new URI(CRI_AUTHORIZE_URL))
                        .clientId(IPV_CLIENT_ID)
                        .signingKey("{}")
                        .encryptionKey(RSA_ENCRYPTION_PUBLIC_JWK)
                        .componentId("http://www.example.com/audience")
                        .clientCallbackUrl(URI.create("http://www.example.com/callback/criId"))
                        .requiresApiKey(true)
                        .requiresAdditionalEvidence(false)
                        .build();

        f2FOauthCriConfig =
                OauthCriConfig.builder()
                        .tokenUrl(new URI(CRI_TOKEN_URL))
                        .credentialUrl(new URI(CRI_CREDENTIAL_URL))
                        .authorizeUrl(new URI(CRI_AUTHORIZE_URL))
                        .clientId(IPV_CLIENT_ID)
                        .signingKey("{}")
                        .encryptionKey(RSA_ENCRYPTION_PUBLIC_JWK)
                        .componentId("http://www.example.com/audience")
                        .clientCallbackUrl(URI.create("http://www.example.com/callback/criId"))
                        .requiresApiKey(true)
                        .requiresAdditionalEvidence(false)
                        .build();

        claimedIdentityOauthCriConfig =
                OauthCriConfig.builder()
                        .tokenUrl(new URI(CRI_TOKEN_URL))
                        .credentialUrl(new URI(CRI_CREDENTIAL_URL))
                        .authorizeUrl(new URI(CRI_AUTHORIZE_URL))
                        .clientId(IPV_CLIENT_ID)
                        .signingKey("{}")
                        .encryptionKey(RSA_ENCRYPTION_PUBLIC_JWK)
                        .componentId("http://www.example.com/audience")
                        .clientCallbackUrl(URI.create("http://www.example.com/callback/criId"))
                        .requiresApiKey(true)
                        .requiresAdditionalEvidence(false)
                        .build();

        hmrcKbvOauthCriConfig =
                OauthCriConfig.builder()
                        .tokenUrl(new URI(CRI_TOKEN_URL))
                        .credentialUrl(new URI(CRI_CREDENTIAL_URL))
                        .authorizeUrl(new URI(CRI_AUTHORIZE_URL))
                        .clientId(IPV_CLIENT_ID)
                        .signingKey("{}")
                        .encryptionKey(RSA_ENCRYPTION_PUBLIC_JWK)
                        .componentId("http://www.example.com/audience")
                        .clientCallbackUrl(URI.create("http://www.example.com/callback/criId"))
                        .requiresApiKey(true)
                        .requiresAdditionalEvidence(false)
                        .build();

        clientOAuthSessionItem =
                ClientOAuthSessionItem.builder()
                        .clientOAuthSessionId(TEST_CLIENT_OAUTH_SESSION_ID)
                        .state("test-state")
                        .responseType("code")
                        .redirectUri("https://example.com/redirect")
                        .govukSigninJourneyId("test-journey-id")
                        .userId(TEST_USER_ID)
                        .clientId("test-client")
                        .build();

        ipvSessionItem.setIpvSessionId(SESSION_ID);
        ipvSessionItem.setClientOAuthSessionId(TEST_CLIENT_OAUTH_SESSION_ID);
        ipvSessionItem.setEmailAddress(TEST_EMAIL_ADDRESS);
    }

    @Test
    void shouldReceive400ResponseCodeIfCredentialIssuerNotPresent() throws JsonProcessingException {
        // Arrange
        var input =
                JourneyRequest.builder()
                        .ipvSessionId("aSessionId")
                        .ipAddress(TEST_IP_ADDRESS)
                        .journey("nope")
                        .build();

        // Act
        var response = handleRequest(input, context);

        // Assert
        assertErrorResponse(
                HttpStatus.SC_BAD_REQUEST, response, ErrorResponse.MISSING_CREDENTIAL_ISSUER_ID);
    }

    @Test
    void shouldReceive400ResponseCodeIfCredentialIssuerNotInPermittedSet()
            throws JsonProcessingException {
        // Arrange
        JourneyRequest input =
                JourneyRequest.builder()
                        .ipvSessionId("aSessionId")
                        .ipAddress(TEST_IP_ADDRESS)
                        .journey(String.format(JOURNEY_BASE_URL, "bad"))
                        .build();

        // Act
        var response = handleRequest(input, context);

        // Assert
        assertErrorResponse(
                HttpStatus.SC_BAD_REQUEST, response, ErrorResponse.INVALID_CREDENTIAL_ISSUER_ID);
    }

    @Test
    void shouldReceive200ResponseCodeAndReturnCredentialIssuerResponseWithoutResponseTypeParam()
            throws Exception {
        // Arrange
        when(configService.getActiveConnection(CRI_ID)).thenReturn(MAIN_CONNECTION);
        when(configService.getOauthCriConfigForConnection(MAIN_CONNECTION, CRI_ID))
                .thenReturn(oauthCriConfig);
        when(configService.getSsmParameter(JWT_TTL_SECONDS)).thenReturn("900");
        when(configService.getSsmParameter(COMPONENT_ID)).thenReturn(IPV_ISSUER);
        when(configService.getComponentId(ADDRESS_CRI))
                .thenReturn(addressOauthCriConfig.getComponentId());
        when(mockIpvSessionService.getIpvSession(SESSION_ID)).thenReturn(ipvSessionItem);
        mockVcHelper.when(() -> VcHelper.isSuccessfulVc(any())).thenReturn(true, true);
        List<VerifiableCredential> vcs =
                List.of(
                        generateVerifiableCredential(
                                TEST_USER_ID,
                                ADDRESS_CRI,
                                vcClaim(CREDENTIAL_ATTRIBUTES_1),
                                IPV_ISSUER),
                        generateVerifiableCredential(
                                TEST_USER_ID,
                                ADDRESS_CRI,
                                vcClaim(CREDENTIAL_ATTRIBUTES_2),
                                IPV_ISSUER));
        when(mockSessionCredentialService.getCredentials(SESSION_ID, TEST_USER_ID)).thenReturn(vcs);
        when(VcHelper.filterVCBasedOnProfileType(any(), any())).thenCallRealMethod();
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockKmsEs256SignerFactory.getSigner(any()))
                .thenReturn(new ECDSASigner(getSigningPrivateKey()));

        JourneyRequest input =
                JourneyRequest.builder()
                        .ipvSessionId(SESSION_ID)
                        .ipAddress(TEST_IP_ADDRESS)
                        .journey(String.format(JOURNEY_BASE_URL, CRI_ID))
                        .build();

        // Act
        var responseJson = handleRequest(input, context);

        // Assert
        CriResponse response = OBJECT_MAPPER.readValue(responseJson, CriResponse.class);

        URIBuilder redirectUri = new URIBuilder(response.getCri().getRedirectUrl());
        List<NameValuePair> queryParams = redirectUri.getQueryParams();

        assertEquals(CRI_ID, response.getCri().getId());

        Optional<NameValuePair> client_id =
                queryParams.stream()
                        .filter(param -> param.getName().equals("client_id"))
                        .findFirst();
        assertTrue(client_id.isPresent());
        assertEquals(IPV_CLIENT_ID, client_id.get().getValue());

        Optional<NameValuePair> response_type =
                queryParams.stream()
                        .filter(param -> param.getName().equals("response_type"))
                        .findFirst();
        assertFalse(response_type.isPresent());

        Optional<NameValuePair> request =
                queryParams.stream().filter(param -> param.getName().equals("request")).findFirst();
        assertTrue(request.isPresent());
        JWEObject jweObject = JWEObject.parse(request.get().getValue());
        jweObject.decrypt(new RSADecrypter(getEncryptionPrivateKey()));
        assertSharedClaimsJWTIsValid(jweObject.getPayload().toString());

        assertEquals(CRI_AUTHORIZE_URL, redirectUri.removeQuery().build().toString());

        ArgumentCaptor<AuditEvent> auditEventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(mockAuditService).sendAuditEvent(auditEventCaptor.capture());
        assertEquals(
                AuditEventTypes.IPV_REDIRECT_TO_CRI, auditEventCaptor.getValue().getEventName());
        verify(mockIpvSessionService, times(1)).updateIpvSession(any());
        verify(mockCriOAuthSessionService, times(1))
                .persistCriOAuthSession(any(), any(), any(), eq(MAIN_CONNECTION));
        verify(mockClientOAuthSessionDetailsService, times(1)).getClientOAuthSession(any());
    }

    @Test
    void
            shouldReceive200ResponseCodeAndReturnCredentialIssuerResponseWithoutResponseTypeParamForAllVCsAreNotSuccess()
                    throws Exception {
        // Arrange
        when(configService.getActiveConnection(CRI_ID)).thenReturn(MAIN_CONNECTION);
        when(configService.getOauthCriConfigForConnection(MAIN_CONNECTION, CRI_ID))
                .thenReturn(oauthCriConfig);
        when(configService.getSsmParameter(JWT_TTL_SECONDS)).thenReturn("900");
        when(configService.getSsmParameter(COMPONENT_ID)).thenReturn(IPV_ISSUER);
        when(mockIpvSessionService.getIpvSession(SESSION_ID)).thenReturn(ipvSessionItem);
        mockVcHelper.when(() -> VcHelper.isSuccessfulVc(any())).thenReturn(false, false);

        when(mockSessionCredentialService.getCredentials(SESSION_ID, TEST_USER_ID))
                .thenReturn(
                        List.of(
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_1),
                                        IPV_ISSUER),
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_2),
                                        IPV_ISSUER)));

        when(VcHelper.filterVCBasedOnProfileType(any(), any())).thenCallRealMethod();

        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockKmsEs256SignerFactory.getSigner(any()))
                .thenReturn(new ECDSASigner(getSigningPrivateKey()));

        JourneyRequest input =
                JourneyRequest.builder()
                        .ipvSessionId(SESSION_ID)
                        .ipAddress(TEST_IP_ADDRESS)
                        .journey(String.format(JOURNEY_BASE_URL, CRI_ID))
                        .build();

        // Act
        var responseJson = handleRequest(input, context);

        // Assert
        CriResponse response = OBJECT_MAPPER.readValue(responseJson, CriResponse.class);

        URIBuilder redirectUri = new URIBuilder(response.getCri().getRedirectUrl());
        List<NameValuePair> queryParams = redirectUri.getQueryParams();

        assertEquals(CRI_ID, response.getCri().getId());

        Optional<NameValuePair> client_id =
                queryParams.stream()
                        .filter(param -> param.getName().equals("client_id"))
                        .findFirst();
        assertTrue(client_id.isPresent());
        assertEquals(IPV_CLIENT_ID, client_id.get().getValue());

        Optional<NameValuePair> response_type =
                queryParams.stream()
                        .filter(param -> param.getName().equals("response_type"))
                        .findFirst();
        assertFalse(response_type.isPresent());

        Optional<NameValuePair> request =
                queryParams.stream().filter(param -> param.getName().equals("request")).findFirst();
        assertTrue(request.isPresent());
        JWEObject jweObject = JWEObject.parse(request.get().getValue());
        jweObject.decrypt(new RSADecrypter(getEncryptionPrivateKey()));
        assertSharedClaimsJWTIsValidForAllVCsAreNotSuccess(jweObject.getPayload().toString());

        assertEquals(CRI_AUTHORIZE_URL, redirectUri.removeQuery().build().toString());

        ArgumentCaptor<AuditEvent> auditEventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(mockAuditService).sendAuditEvent(auditEventCaptor.capture());
        assertEquals(
                AuditEventTypes.IPV_REDIRECT_TO_CRI, auditEventCaptor.getValue().getEventName());
        verify(mockIpvSessionService, times(1)).updateIpvSession(any());
        verify(mockCriOAuthSessionService, times(1))
                .persistCriOAuthSession(any(), any(), any(), eq(MAIN_CONNECTION));
        verify(mockClientOAuthSessionDetailsService, times(1)).getClientOAuthSession(any());
    }

    @Test
    void
            shouldReceive200ResponseCodeAndReturnCredentialIssuerResponseWithFullUrlJourneyAndWithoutResponseTypeParam()
                    throws Exception {
        // Arrange
        when(configService.getActiveConnection(CRI_ID)).thenReturn(MAIN_CONNECTION);
        when(configService.getOauthCriConfigForConnection(MAIN_CONNECTION, CRI_ID))
                .thenReturn(oauthCriConfig);
        when(configService.getSsmParameter(JWT_TTL_SECONDS)).thenReturn("900");
        when(configService.getSsmParameter(COMPONENT_ID)).thenReturn(IPV_ISSUER);
        when(configService.getComponentId(ADDRESS_CRI))
                .thenReturn(addressOauthCriConfig.getComponentId());
        when(mockIpvSessionService.getIpvSession(SESSION_ID)).thenReturn(ipvSessionItem);
        mockVcHelper.when(() -> VcHelper.isSuccessfulVc(any())).thenReturn(true, true);
        when(mockSessionCredentialService.getCredentials(any(), any()))
                .thenReturn(
                        List.of(
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_1),
                                        IPV_ISSUER),
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_2),
                                        IPV_ISSUER)));
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockKmsEs256SignerFactory.getSigner(any()))
                .thenReturn(new ECDSASigner(getSigningPrivateKey()));

        JourneyRequest input =
                JourneyRequest.builder()
                        .ipvSessionId(SESSION_ID)
                        .ipAddress(TEST_IP_ADDRESS)
                        .journey(String.format(JOURNEY_BASE_URL, CRI_ID))
                        .build();

        // Act
        var responseJson = handleRequest(input, context);

        // Assert
        CriResponse response = OBJECT_MAPPER.readValue(responseJson, CriResponse.class);

        URIBuilder redirectUri = new URIBuilder(response.getCri().getRedirectUrl());
        List<NameValuePair> queryParams = redirectUri.getQueryParams();

        assertEquals(CRI_ID, response.getCri().getId());

        Optional<NameValuePair> client_id =
                queryParams.stream()
                        .filter(param -> param.getName().equals("client_id"))
                        .findFirst();
        assertTrue(client_id.isPresent());
        assertEquals(IPV_CLIENT_ID, client_id.get().getValue());

        Optional<NameValuePair> response_type =
                queryParams.stream()
                        .filter(param -> param.getName().equals("response_type"))
                        .findFirst();
        assertFalse(response_type.isPresent());

        Optional<NameValuePair> request =
                queryParams.stream().filter(param -> param.getName().equals("request")).findFirst();
        assertTrue(request.isPresent());
        JWEObject jweObject = JWEObject.parse(request.get().getValue());
        jweObject.decrypt(new RSADecrypter(getEncryptionPrivateKey()));
        assertSharedClaimsJWTIsValid(jweObject.getPayload().toString());

        assertEquals(CRI_AUTHORIZE_URL, redirectUri.removeQuery().build().toString());

        ArgumentCaptor<AuditEvent> auditEventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(mockAuditService).sendAuditEvent(auditEventCaptor.capture());
        assertEquals(
                AuditEventTypes.IPV_REDIRECT_TO_CRI, auditEventCaptor.getValue().getEventName());
        verify(mockSessionCredentialService).getCredentials(SESSION_ID, TEST_USER_ID);
        verify(mockIpvSessionService, times(1)).updateIpvSession(any());
        verify(mockCriOAuthSessionService, times(1))
                .persistCriOAuthSession(any(), any(), any(), eq(MAIN_CONNECTION));
        verify(mockClientOAuthSessionDetailsService, times(1)).getClientOAuthSession(any());
    }

    @Test
    void
            shouldReceive200ResponseCodeAndReturnCredentialIssuerResponseWithoutBaseJourneyUrlAndResponseTypeParam()
                    throws Exception {
        // Arrange
        when(configService.getActiveConnection(CRI_ID)).thenReturn(MAIN_CONNECTION);
        when(configService.getOauthCriConfigForConnection(MAIN_CONNECTION, CRI_ID))
                .thenReturn(oauthCriConfig);
        when(configService.getSsmParameter(JWT_TTL_SECONDS)).thenReturn("900");
        when(configService.getSsmParameter(COMPONENT_ID)).thenReturn(IPV_ISSUER);
        when(configService.getComponentId(ADDRESS_CRI))
                .thenReturn(addressOauthCriConfig.getComponentId());
        when(mockIpvSessionService.getIpvSession(SESSION_ID)).thenReturn(ipvSessionItem);
        mockVcHelper.when(() -> VcHelper.isSuccessfulVc(any())).thenReturn(true, true);
        when(mockSessionCredentialService.getCredentials(SESSION_ID, TEST_USER_ID))
                .thenReturn(
                        List.of(
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_1),
                                        IPV_ISSUER),
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_2),
                                        IPV_ISSUER)));
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockKmsEs256SignerFactory.getSigner(any()))
                .thenReturn(new ECDSASigner(getSigningPrivateKey()));

        JourneyRequest input =
                JourneyRequest.builder()
                        .ipvSessionId(SESSION_ID)
                        .ipAddress(TEST_IP_ADDRESS)
                        .journey(String.format(JOURNEY_BASE_URL, CRI_ID))
                        .build();

        // Act
        var responseJson = handleRequest(input, context);

        // Assert
        CriResponse response = OBJECT_MAPPER.readValue(responseJson, CriResponse.class);

        URIBuilder redirectUri = new URIBuilder(response.getCri().getRedirectUrl());
        List<NameValuePair> queryParams = redirectUri.getQueryParams();

        assertEquals(CRI_ID, response.getCri().getId());

        Optional<NameValuePair> client_id =
                queryParams.stream()
                        .filter(param -> param.getName().equals("client_id"))
                        .findFirst();
        assertTrue(client_id.isPresent());
        assertEquals(IPV_CLIENT_ID, client_id.get().getValue());

        Optional<NameValuePair> response_type =
                queryParams.stream()
                        .filter(param -> param.getName().equals("response_type"))
                        .findFirst();
        assertFalse(response_type.isPresent());

        Optional<NameValuePair> request =
                queryParams.stream().filter(param -> param.getName().equals("request")).findFirst();
        assertTrue(request.isPresent());
        JWEObject jweObject = JWEObject.parse(request.get().getValue());
        jweObject.decrypt(new RSADecrypter(getEncryptionPrivateKey()));
        assertSharedClaimsJWTIsValid(jweObject.getPayload().toString());

        assertEquals(CRI_AUTHORIZE_URL, redirectUri.removeQuery().build().toString());

        ArgumentCaptor<AuditEvent> auditEventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(mockAuditService).sendAuditEvent(auditEventCaptor.capture());
        assertEquals(
                AuditEventTypes.IPV_REDIRECT_TO_CRI, auditEventCaptor.getValue().getEventName());
        verify(mockSessionCredentialService).getCredentials(SESSION_ID, TEST_USER_ID);
        verify(mockIpvSessionService, times(1)).updateIpvSession(any());
        verify(mockCriOAuthSessionService, times(1))
                .persistCriOAuthSession(any(), any(), any(), eq(MAIN_CONNECTION));
        verify(mockClientOAuthSessionDetailsService, times(1)).getClientOAuthSession(any());
    }

    @Test
    void shouldReceive200ResponseCodeAndReturnCredentialIssuerResponseWithResponseTypeParam()
            throws Exception {
        // Arrange
        when(configService.getActiveConnection(DCMAW_CRI)).thenReturn(MAIN_CONNECTION);
        when(configService.getOauthCriConfigForConnection(MAIN_CONNECTION, DCMAW_CRI))
                .thenReturn(dcmawOauthCriConfig);
        when(configService.getSsmParameter(JWT_TTL_SECONDS)).thenReturn("900");
        when(configService.getSsmParameter(COMPONENT_ID)).thenReturn(IPV_ISSUER);
        when(configService.getComponentId(ADDRESS_CRI))
                .thenReturn(addressOauthCriConfig.getComponentId());
        when(mockIpvSessionService.getIpvSession(SESSION_ID)).thenReturn(ipvSessionItem);
        mockVcHelper.when(() -> VcHelper.isSuccessfulVc(any())).thenReturn(true, true);
        when(mockSessionCredentialService.getCredentials(SESSION_ID, TEST_USER_ID))
                .thenReturn(
                        List.of(
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_1),
                                        IPV_ISSUER),
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_2),
                                        IPV_ISSUER)));
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockKmsEs256SignerFactory.getSigner(any()))
                .thenReturn(new ECDSASigner(getSigningPrivateKey()));

        JourneyRequest input =
                JourneyRequest.builder()
                        .ipvSessionId(SESSION_ID)
                        .ipAddress(TEST_IP_ADDRESS)
                        .journey(String.format(JOURNEY_BASE_URL, DCMAW_CRI))
                        .build();

        // Act
        var responseJson = handleRequest(input, context);

        // Assert
        CriResponse response = OBJECT_MAPPER.readValue(responseJson, CriResponse.class);

        URIBuilder redirectUri = new URIBuilder(response.getCri().getRedirectUrl());
        List<NameValuePair> queryParams = redirectUri.getQueryParams();

        assertEquals(DCMAW_CRI, response.getCri().getId());

        Optional<NameValuePair> client_id =
                queryParams.stream()
                        .filter(param -> param.getName().equals("client_id"))
                        .findFirst();
        assertTrue(client_id.isPresent());
        assertEquals(IPV_CLIENT_ID, client_id.get().getValue());

        Optional<NameValuePair> response_type =
                queryParams.stream()
                        .filter(param -> param.getName().equals("response_type"))
                        .findFirst();
        assertTrue(response_type.isPresent());
        assertEquals("code", response_type.get().getValue());

        Optional<NameValuePair> request =
                queryParams.stream().filter(param -> param.getName().equals("request")).findFirst();
        assertTrue(request.isPresent());
        JWEObject jweObject = JWEObject.parse(request.get().getValue());
        jweObject.decrypt(new RSADecrypter(getEncryptionPrivateKey()));
        assertSharedClaimsJWTIsValid(jweObject.getPayload().toString());

        assertEquals(CRI_AUTHORIZE_URL, redirectUri.removeQuery().build().toString());

        ArgumentCaptor<AuditEvent> auditEventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(mockAuditService).sendAuditEvent(auditEventCaptor.capture());
        assertEquals(
                AuditEventTypes.IPV_REDIRECT_TO_CRI, auditEventCaptor.getValue().getEventName());
        verify(mockSessionCredentialService).getCredentials(SESSION_ID, TEST_USER_ID);
        verify(mockIpvSessionService, times(1)).updateIpvSession(any());
        verify(mockCriOAuthSessionService, times(1))
                .persistCriOAuthSession(any(), any(), any(), eq(MAIN_CONNECTION));
        verify(mockClientOAuthSessionDetailsService, times(1)).getClientOAuthSession(any());
    }

    @Test
    void shouldReturn400IfSessionIdIsNull() throws JsonProcessingException {
        // Arrange
        JourneyRequest input = JourneyRequest.builder().journey(CRI_ID).build();

        // Act
        var responseJson = handleRequest(input, context);

        // Assert
        JourneyErrorResponse response =
                OBJECT_MAPPER.readValue(responseJson, JourneyErrorResponse.class);
        assertEquals(HttpStatus.SC_BAD_REQUEST, response.getStatusCode());
        assertEquals(ErrorResponse.MISSING_IPV_SESSION_ID.getCode(), response.getCode());
        assertEquals(ErrorResponse.MISSING_IPV_SESSION_ID.getMessage(), response.getMessage());
    }

    private void assertSharedClaimsJWTIsValid(String request)
            throws ParseException, JsonProcessingException, JOSEException {
        // Arrange
        SignedJWT signedJWT = SignedJWT.parse(request);

        // Act
        JsonNode claimsSet = OBJECT_MAPPER.readTree(signedJWT.getJWTClaimsSet().toString());

        // Assert
        assertEquals(IPV_CLIENT_ID, signedJWT.getJWTClaimsSet().getClaim("client_id"));
        assertEquals(IPV_ISSUER, signedJWT.getJWTClaimsSet().getIssuer());
        assertEquals(TEST_USER_ID, signedJWT.getJWTClaimsSet().getSubject());
        assertEquals(CRI_AUDIENCE, signedJWT.getJWTClaimsSet().getAudience().get(0));

        assertEquals(3, claimsSet.get(TEST_SHARED_CLAIMS).size());
        JsonNode vcAttributes = claimsSet.get(TEST_SHARED_CLAIMS);

        JsonNode address = vcAttributes.get("address");

        List<Address> addressList = new ArrayList<>();
        for (JsonNode jo : address) {
            addressList.add(OBJECT_MAPPER.convertValue(jo, Address.class));
        }

        Address streetAddress =
                addressList.stream()
                        .filter(x -> "NotDowningStreet".equals(x.getStreetName()))
                        .findAny()
                        .orElse(null);
        Address postCode =
                addressList.stream()
                        .filter(x -> "SW1A2AA".equals(x.getPostalCode()))
                        .findAny()
                        .orElse(null);

        assertFalse(streetAddress.getStreetName().isEmpty());
        assertFalse(postCode.getPostalCode().isEmpty());

        assertEquals(2, (vcAttributes.get("name")).size());
        assertEquals(3, (vcAttributes.get("address")).size());
        assertEquals(2, (vcAttributes.get("birthDate")).size());

        ECDSAVerifier verifier = new ECDSAVerifier(ECKey.parse(EC_PUBLIC_JWK));
        assertTrue(signedJWT.verify(verifier));
    }

    private void assertSharedClaimsJWTIsValidForAllVCsAreNotSuccess(String request)
            throws ParseException, JsonProcessingException, JOSEException {
        // Arrange
        SignedJWT signedJWT = SignedJWT.parse(request);

        // Act
        JsonNode claimsSet = OBJECT_MAPPER.readTree(signedJWT.getJWTClaimsSet().toString());

        // Assert
        assertEquals(IPV_CLIENT_ID, signedJWT.getJWTClaimsSet().getClaim("client_id"));
        assertEquals(IPV_ISSUER, signedJWT.getJWTClaimsSet().getIssuer());
        assertEquals(TEST_USER_ID, signedJWT.getJWTClaimsSet().getSubject());
        assertEquals(CRI_AUDIENCE, signedJWT.getJWTClaimsSet().getAudience().get(0));

        assertEquals(3, claimsSet.get(TEST_SHARED_CLAIMS).size());
        JsonNode vcAttributes = claimsSet.get(TEST_SHARED_CLAIMS);

        JsonNode address = vcAttributes.get("address");
        assertTrue(address.isEmpty());
        JsonNode name = vcAttributes.get("name");
        assertTrue(name.isEmpty());
        JsonNode birtDate = vcAttributes.get("birthDate");
        assertTrue(birtDate.isEmpty());

        ECDSAVerifier verifier = new ECDSAVerifier(ECKey.parse(EC_PUBLIC_JWK));
        assertTrue(signedJWT.verify(verifier));
    }

    @Test
    void shouldDeduplicateSharedClaims() throws Exception {
        // Arrange
        when(configService.getActiveConnection(CRI_ID)).thenReturn(MAIN_CONNECTION);
        when(configService.getOauthCriConfigForConnection(MAIN_CONNECTION, CRI_ID))
                .thenReturn(oauthCriConfig);
        when(configService.getSsmParameter(JWT_TTL_SECONDS)).thenReturn("900");
        when(configService.getSsmParameter(COMPONENT_ID)).thenReturn(IPV_ISSUER);
        when(configService.getComponentId(ADDRESS_CRI))
                .thenReturn(addressOauthCriConfig.getComponentId());
        when(mockIpvSessionService.getIpvSession(SESSION_ID)).thenReturn(ipvSessionItem);
        mockVcHelper.when(() -> VcHelper.isSuccessfulVc(any())).thenReturn(true, true);
        when(mockSessionCredentialService.getCredentials(SESSION_ID, TEST_USER_ID))
                .thenReturn(
                        List.of(
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_1),
                                        IPV_ISSUER),
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_1),
                                        IPV_ISSUER)));
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockKmsEs256SignerFactory.getSigner(any()))
                .thenReturn(new ECDSASigner(getSigningPrivateKey()));

        JourneyRequest input =
                JourneyRequest.builder()
                        .ipvSessionId(SESSION_ID)
                        .ipAddress(TEST_IP_ADDRESS)
                        .journey(String.format(JOURNEY_BASE_URL, CRI_ID))
                        .build();

        // Act
        var responseJson = handleRequest(input, context);

        // Assert
        CriResponse response = OBJECT_MAPPER.readValue(responseJson, CriResponse.class);

        URIBuilder redirectUri = new URIBuilder(response.getCri().getRedirectUrl());
        List<NameValuePair> queryParams = redirectUri.getQueryParams();

        Optional<NameValuePair> request =
                queryParams.stream().filter(param -> param.getName().equals("request")).findFirst();

        assertTrue(request.isPresent());
        JWEObject jweObject = JWEObject.parse(request.get().getValue());
        jweObject.decrypt(new RSADecrypter(getEncryptionPrivateKey()));

        SignedJWT signedJWT = SignedJWT.parse(jweObject.getPayload().toString());
        JsonNode claimsSet = OBJECT_MAPPER.readTree(signedJWT.getJWTClaimsSet().toString());

        JsonNode sharedClaims = claimsSet.get(TEST_SHARED_CLAIMS);
        assertEquals(1, sharedClaims.get("name").size());
        assertEquals(2, sharedClaims.get("birthDate").size());
        assertEquals(2, sharedClaims.get("address").size());
        verify(mockIpvSessionService, times(1)).updateIpvSession(any());
        verify(mockCriOAuthSessionService, times(1))
                .persistCriOAuthSession(any(), any(), any(), eq(MAIN_CONNECTION));
        verify(mockClientOAuthSessionDetailsService, times(1)).getClientOAuthSession(any());
    }

    @Test
    void shouldNotDeduplicateSharedClaimsIfFullNameDifferent() throws Exception {
        // Arrange
        when(configService.getActiveConnection(CRI_ID)).thenReturn(MAIN_CONNECTION);
        when(configService.getOauthCriConfigForConnection(MAIN_CONNECTION, CRI_ID))
                .thenReturn(oauthCriConfig);
        when(configService.getSsmParameter(JWT_TTL_SECONDS)).thenReturn("900");
        when(configService.getSsmParameter(COMPONENT_ID)).thenReturn(IPV_ISSUER);
        when(configService.getComponentId(ADDRESS_CRI))
                .thenReturn(addressOauthCriConfig.getComponentId());
        when(mockIpvSessionService.getIpvSession(SESSION_ID)).thenReturn(ipvSessionItem);
        mockVcHelper.when(() -> VcHelper.isSuccessfulVc(any())).thenReturn(true, true);
        when(mockSessionCredentialService.getCredentials(SESSION_ID, TEST_USER_ID))
                .thenReturn(
                        List.of(
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_2),
                                        IPV_ISSUER),
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_3),
                                        IPV_ISSUER)));
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockKmsEs256SignerFactory.getSigner(any()))
                .thenReturn(new ECDSASigner(getSigningPrivateKey()));

        JourneyRequest input =
                JourneyRequest.builder()
                        .ipvSessionId(SESSION_ID)
                        .ipAddress(TEST_IP_ADDRESS)
                        .journey(String.format(JOURNEY_BASE_URL, CRI_ID))
                        .build();

        // Act
        var responseJson = handleRequest(input, context);

        // Assert
        CriResponse response = OBJECT_MAPPER.readValue(responseJson, CriResponse.class);

        URIBuilder redirectUri = new URIBuilder(response.getCri().getRedirectUrl());
        List<NameValuePair> queryParams = redirectUri.getQueryParams();

        Optional<NameValuePair> request =
                queryParams.stream().filter(param -> param.getName().equals("request")).findFirst();

        assertTrue(request.isPresent());
        JWEObject jweObject = JWEObject.parse(request.get().getValue());
        jweObject.decrypt(new RSADecrypter(getEncryptionPrivateKey()));

        SignedJWT signedJWT = SignedJWT.parse(jweObject.getPayload().toString());
        JsonNode claimsSet = OBJECT_MAPPER.readTree(signedJWT.getJWTClaimsSet().toString());

        JsonNode sharedClaims = claimsSet.get(TEST_SHARED_CLAIMS);
        assertEquals(2, sharedClaims.get("name").size());
        verify(mockIpvSessionService, times(1)).updateIpvSession(any());
        verify(mockCriOAuthSessionService, times(1))
                .persistCriOAuthSession(any(), any(), any(), eq(MAIN_CONNECTION));
        verify(mockClientOAuthSessionDetailsService, times(1)).getClientOAuthSession(any());
    }

    @Test
    void shouldDeduplicateNamesThatAppearInDifferentVCs() throws Exception {
        // Arrange
        when(configService.getActiveConnection(CRI_ID)).thenReturn(MAIN_CONNECTION);
        when(configService.getOauthCriConfigForConnection(MAIN_CONNECTION, CRI_ID))
                .thenReturn(oauthCriConfig);
        when(configService.getSsmParameter(JWT_TTL_SECONDS)).thenReturn("900");
        when(configService.getSsmParameter(COMPONENT_ID)).thenReturn(IPV_ISSUER);
        when(configService.getComponentId(ADDRESS_CRI))
                .thenReturn(addressOauthCriConfig.getComponentId());
        when(mockIpvSessionService.getIpvSession(SESSION_ID)).thenReturn(ipvSessionItem);
        mockVcHelper.when(() -> VcHelper.isSuccessfulVc(any())).thenReturn(true, true);
        when(mockSessionCredentialService.getCredentials(SESSION_ID, TEST_USER_ID))
                .thenReturn(
                        List.of(
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_3),
                                        IPV_ISSUER),
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_4),
                                        IPV_ISSUER)));
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockKmsEs256SignerFactory.getSigner(any()))
                .thenReturn(new ECDSASigner(getSigningPrivateKey()));

        JourneyRequest input =
                JourneyRequest.builder()
                        .ipvSessionId(SESSION_ID)
                        .ipAddress(TEST_IP_ADDRESS)
                        .journey(String.format(JOURNEY_BASE_URL, CRI_ID))
                        .build();

        // Act
        var responseJson = handleRequest(input, context);

        // Assert
        CriResponse response = OBJECT_MAPPER.readValue(responseJson, CriResponse.class);

        URIBuilder redirectUri = new URIBuilder(response.getCri().getRedirectUrl());
        List<NameValuePair> queryParams = redirectUri.getQueryParams();

        Optional<NameValuePair> request =
                queryParams.stream().filter(param -> param.getName().equals("request")).findFirst();

        assertTrue(request.isPresent());
        JWEObject jweObject = JWEObject.parse(request.get().getValue());
        jweObject.decrypt(new RSADecrypter(getEncryptionPrivateKey()));

        SignedJWT signedJWT = SignedJWT.parse(jweObject.getPayload().toString());
        JsonNode claimsSet = OBJECT_MAPPER.readTree(signedJWT.getJWTClaimsSet().toString());

        JsonNode names = claimsSet.get(TEST_SHARED_CLAIMS).get("name");
        JsonNode name1NameParts = names.get(1).get("nameParts");
        JsonNode name2NameParts = names.get(0).get("nameParts");

        assertEquals("GivenName", name1NameParts.get(0).get("type").asText());
        assertEquals("Alice", name1NameParts.get(0).get("value").asText());
        assertEquals("GivenName", name1NameParts.get(1).get("type").asText());
        assertEquals("Jane", name1NameParts.get(1).get("value").asText());
        assertEquals("GivenName", name1NameParts.get(2).get("type").asText());
        assertEquals("Laura", name1NameParts.get(2).get("value").asText());
        assertEquals("FamilyName", name1NameParts.get(3).get("type").asText());
        assertEquals("Doe", name1NameParts.get(3).get("value").asText());
        assertEquals("FamilyName", name1NameParts.get(4).get("type").asText());
        assertEquals("Musk", name1NameParts.get(4).get("value").asText());

        assertEquals("GivenName", name2NameParts.get(0).get("type").asText());
        assertEquals("Alice", name2NameParts.get(0).get("value").asText());
        assertEquals("GivenName", name2NameParts.get(1).get("type").asText());
        assertEquals("Jane", name2NameParts.get(1).get("value").asText());
        assertEquals("FamilyName", name2NameParts.get(2).get("type").asText());
        assertEquals("Doe", name2NameParts.get(2).get("value").asText());
        verify(mockIpvSessionService, times(1)).updateIpvSession(any());
        verify(mockCriOAuthSessionService, times(1))
                .persistCriOAuthSession(any(), any(), any(), eq(MAIN_CONNECTION));
        verify(mockClientOAuthSessionDetailsService, times(1)).getClientOAuthSession(any());
    }

    @Test
    void shouldRemoveExtraAddressClaimsAndOnlyUseValuesFromTheAddressVC() throws Exception {
        // Arrange
        when(configService.getActiveConnection(CRI_ID)).thenReturn(MAIN_CONNECTION);
        when(configService.getOauthCriConfigForConnection(MAIN_CONNECTION, CRI_ID))
                .thenReturn(oauthCriConfig);
        when(configService.getSsmParameter(JWT_TTL_SECONDS)).thenReturn("900");
        when(configService.getSsmParameter(COMPONENT_ID)).thenReturn(IPV_ISSUER);
        when(configService.getComponentId(ADDRESS_CRI))
                .thenReturn(addressOauthCriConfig.getComponentId());
        when(mockIpvSessionService.getIpvSession(SESSION_ID)).thenReturn(ipvSessionItem);
        mockVcHelper.when(() -> VcHelper.isSuccessfulVc(any())).thenReturn(true, true);
        when(mockSessionCredentialService.getCredentials(SESSION_ID, TEST_USER_ID))
                .thenReturn(
                        List.of(
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_1),
                                        IPV_ISSUER),
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_2),
                                        IPV_ISSUER),
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_3),
                                        ADDRESS_ISSUER)));
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockKmsEs256SignerFactory.getSigner(any()))
                .thenReturn(new ECDSASigner(getSigningPrivateKey()));

        JourneyRequest input =
                JourneyRequest.builder()
                        .ipvSessionId(SESSION_ID)
                        .ipAddress(TEST_IP_ADDRESS)
                        .journey(String.format(JOURNEY_BASE_URL, CRI_ID))
                        .build();

        // Act
        var responseJson = handleRequest(input, context);

        // Assert
        CriResponse response = OBJECT_MAPPER.readValue(responseJson, CriResponse.class);

        URIBuilder redirectUri = new URIBuilder(response.getCri().getRedirectUrl());
        List<NameValuePair> queryParams = redirectUri.getQueryParams();

        Optional<NameValuePair> request =
                queryParams.stream().filter(param -> param.getName().equals("request")).findFirst();

        assertTrue(request.isPresent());
        JWEObject jweObject = JWEObject.parse(request.get().getValue());
        jweObject.decrypt(new RSADecrypter(getEncryptionPrivateKey()));

        SignedJWT signedJWT = SignedJWT.parse(jweObject.getPayload().toString());
        JsonNode claimsSet = OBJECT_MAPPER.readTree(signedJWT.getJWTClaimsSet().toString());

        JsonNode sharedClaims = claimsSet.get(TEST_SHARED_CLAIMS);
        assertEquals(3, sharedClaims.get("name").size());
        assertEquals(2, sharedClaims.get("birthDate").size());
        assertEquals(1, sharedClaims.get("address").size());
        verify(mockIpvSessionService, times(1)).updateIpvSession(any());
        verify(mockCriOAuthSessionService, times(1))
                .persistCriOAuthSession(any(), any(), any(), eq(MAIN_CONNECTION));
        verify(mockClientOAuthSessionDetailsService, times(1)).getClientOAuthSession(any());
    }

    @Test
    void shouldNotIncludeFailedVcsInTheSharedClaims() throws Exception {
        // Arrange
        when(configService.getActiveConnection(CRI_ID)).thenReturn(MAIN_CONNECTION);
        when(configService.getOauthCriConfigForConnection(MAIN_CONNECTION, CRI_ID))
                .thenReturn(oauthCriConfig);
        when(configService.getSsmParameter(JWT_TTL_SECONDS)).thenReturn("900");
        when(configService.getSsmParameter(COMPONENT_ID)).thenReturn(IPV_ISSUER);
        when(configService.getComponentId(ADDRESS_CRI))
                .thenReturn(addressOauthCriConfig.getComponentId());
        when(mockIpvSessionService.getIpvSession(SESSION_ID)).thenReturn(ipvSessionItem);
        mockVcHelper.when(() -> VcHelper.isSuccessfulVc(any())).thenReturn(true, false);
        when(mockSessionCredentialService.getCredentials(SESSION_ID, TEST_USER_ID))
                .thenReturn(
                        List.of(
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_1),
                                        IPV_ISSUER),
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_2),
                                        ADDRESS_ISSUER)));
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockKmsEs256SignerFactory.getSigner(any()))
                .thenReturn(new ECDSASigner(getSigningPrivateKey()));

        JourneyRequest input =
                JourneyRequest.builder()
                        .ipvSessionId(SESSION_ID)
                        .ipAddress(TEST_IP_ADDRESS)
                        .journey(String.format(JOURNEY_BASE_URL, CRI_ID))
                        .build();

        // Act
        var responseJson = handleRequest(input, context);

        // Assert
        CriResponse response = OBJECT_MAPPER.readValue(responseJson, CriResponse.class);

        URIBuilder redirectUri = new URIBuilder(response.getCri().getRedirectUrl());
        List<NameValuePair> queryParams = redirectUri.getQueryParams();

        Optional<NameValuePair> request =
                queryParams.stream().filter(param -> param.getName().equals("request")).findFirst();

        assertTrue(request.isPresent());
        JWEObject jweObject = JWEObject.parse(request.get().getValue());
        jweObject.decrypt(new RSADecrypter(getEncryptionPrivateKey()));

        SignedJWT signedJWT = SignedJWT.parse(jweObject.getPayload().toString());
        JsonNode claimsSet = OBJECT_MAPPER.readTree(signedJWT.getJWTClaimsSet().toString());

        JsonNode sharedClaims = claimsSet.get(TEST_SHARED_CLAIMS);
        assertEquals(1, sharedClaims.get("name").size());
        assertEquals(2, sharedClaims.get("birthDate").size());
        assertEquals(2, sharedClaims.get("address").size());
        verify(mockSessionCredentialService).getCredentials(SESSION_ID, TEST_USER_ID);
        verify(mockIpvSessionService, times(1)).updateIpvSession(any());
        verify(mockCriOAuthSessionService, times(1))
                .persistCriOAuthSession(any(), any(), any(), eq(MAIN_CONNECTION));
        verify(mockClientOAuthSessionDetailsService, times(1)).getClientOAuthSession(any());
    }

    @Test
    void shouldOnlyAllowCRIConfiguredSharedClaimAttr() throws Exception {
        // Arrange
        when(configService.getActiveConnection(CRI_ID)).thenReturn(MAIN_CONNECTION);
        when(configService.getOauthCriConfigForConnection(MAIN_CONNECTION, CRI_ID))
                .thenReturn(oauthCriConfig);
        when(configService.getAllowedSharedAttributes(CRI_ID))
                .thenReturn("name,birthDate,address,emailAddress");
        when(configService.getSsmParameter(JWT_TTL_SECONDS)).thenReturn("900");
        when(configService.getSsmParameter(COMPONENT_ID)).thenReturn(IPV_ISSUER);
        when(configService.getComponentId(ADDRESS_CRI))
                .thenReturn(addressOauthCriConfig.getComponentId());
        when(mockIpvSessionService.getIpvSession(SESSION_ID)).thenReturn(ipvSessionItem);
        ipvSessionItem.setEmailAddress(null);
        mockVcHelper.when(() -> VcHelper.isSuccessfulVc(any())).thenReturn(true, true);
        when(mockSessionCredentialService.getCredentials(SESSION_ID, TEST_USER_ID))
                .thenReturn(
                        List.of(
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_1),
                                        IPV_ISSUER),
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_2),
                                        IPV_ISSUER),
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_3),
                                        ADDRESS_ISSUER)));
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockKmsEs256SignerFactory.getSigner(any()))
                .thenReturn(new ECDSASigner(getSigningPrivateKey()));

        JourneyRequest input =
                JourneyRequest.builder()
                        .ipvSessionId(SESSION_ID)
                        .ipAddress(TEST_IP_ADDRESS)
                        .journey(String.format(JOURNEY_BASE_URL, CRI_ID))
                        .build();

        // Act
        var responseJson = handleRequest(input, context);

        // Assert
        CriResponse response = OBJECT_MAPPER.readValue(responseJson, CriResponse.class);

        URIBuilder redirectUri = new URIBuilder(response.getCri().getRedirectUrl());
        List<NameValuePair> queryParams = redirectUri.getQueryParams();

        Optional<NameValuePair> request =
                queryParams.stream().filter(param -> param.getName().equals("request")).findFirst();

        assertTrue(request.isPresent());
        JWEObject jweObject = JWEObject.parse(request.get().getValue());
        jweObject.decrypt(new RSADecrypter(getEncryptionPrivateKey()));

        SignedJWT signedJWT = SignedJWT.parse(jweObject.getPayload().toString());
        JsonNode claimsSet = OBJECT_MAPPER.readTree(signedJWT.getJWTClaimsSet().toString());

        JsonNode sharedClaims = claimsSet.get(TEST_SHARED_CLAIMS);
        assertEquals(3, sharedClaims.get("name").size());
        assertEquals(2, sharedClaims.get("birthDate").size());
        assertEquals(1, sharedClaims.get("address").size());
        assertFalse(sharedClaims.has("emailAddress"));
        verify(mockIpvSessionService, times(1)).updateIpvSession(any());
        JsonNode evidenceRequested = claimsSet.get(TEST_EVIDENCE_REQUESTED);
        assertNull(evidenceRequested);
    }

    @Test
    void shouldOnlyEmailForF2FAndAllowCRIConfiguredSharedClaimAttr() throws Exception {
        // Arrange
        when(configService.getActiveConnection(F2F_CRI)).thenReturn(MAIN_CONNECTION);
        when(configService.getOauthCriConfigForConnection(MAIN_CONNECTION, F2F_CRI))
                .thenReturn(f2FOauthCriConfig);
        when(configService.getSsmParameter(JWT_TTL_SECONDS)).thenReturn("900");
        when(configService.getSsmParameter(COMPONENT_ID)).thenReturn(IPV_ISSUER);
        when(configService.getComponentId(ADDRESS_CRI))
                .thenReturn(addressOauthCriConfig.getComponentId());
        when(configService.getAllowedSharedAttributes(F2F_CRI))
                .thenReturn("name,birthDate,address,emailAddress");
        when(mockIpvSessionService.getIpvSession(SESSION_ID)).thenReturn(ipvSessionItem);
        mockVcHelper.when(() -> VcHelper.isSuccessfulVc(any())).thenReturn(true, true);
        when(mockSessionCredentialService.getCredentials(SESSION_ID, TEST_USER_ID))
                .thenReturn(
                        List.of(
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_1),
                                        IPV_ISSUER),
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_2),
                                        IPV_ISSUER),
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_3),
                                        ADDRESS_ISSUER)));
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockGpg45ProfileEvaluator.buildScore(any()))
                .thenReturn(new Gpg45Scores(1, 1, 3, 3, 3));
        when(mockKmsEs256SignerFactory.getSigner(any()))
                .thenReturn(new ECDSASigner(getSigningPrivateKey()));

        JourneyRequest input =
                JourneyRequest.builder()
                        .ipvSessionId(SESSION_ID)
                        .ipAddress(TEST_IP_ADDRESS)
                        .journey(String.format(JOURNEY_BASE_URL, F2F_CRI))
                        .build();

        // Act
        var responseJson = handleRequest(input, context);

        // Assert
        CriResponse response = OBJECT_MAPPER.readValue(responseJson, CriResponse.class);

        URIBuilder redirectUri = new URIBuilder(response.getCri().getRedirectUrl());
        List<NameValuePair> queryParams = redirectUri.getQueryParams();

        Optional<NameValuePair> request =
                queryParams.stream().filter(param -> param.getName().equals("request")).findFirst();

        assertTrue(request.isPresent());
        JWEObject jweObject = JWEObject.parse(request.get().getValue());
        jweObject.decrypt(new RSADecrypter(getEncryptionPrivateKey()));

        SignedJWT signedJWT = SignedJWT.parse(jweObject.getPayload().toString());
        JsonNode claimsSet = OBJECT_MAPPER.readTree(signedJWT.getJWTClaimsSet().toString());

        JsonNode sharedClaims = claimsSet.get(TEST_SHARED_CLAIMS);
        assertEquals(3, sharedClaims.get("name").size());
        assertEquals(2, sharedClaims.get("birthDate").size());
        assertEquals(1, sharedClaims.get("address").size());
        assertEquals(TEST_EMAIL_ADDRESS, sharedClaims.get("emailAddress").asText());
        JsonNode evidenceRequested = claimsSet.get(TEST_EVIDENCE_REQUESTED);
        assertEquals(3, evidenceRequested.get("strengthScore").asInt());
        verify(mockIpvSessionService, times(1)).updateIpvSession(any());
    }

    @Test
    void shouldIncludeSocialSecurityRecordInSharedClaimsIfConfigured() throws Exception {
        // Arrange
        when(configService.getActiveConnection(HMRC_KBV_CRI)).thenReturn(MAIN_CONNECTION);
        when(configService.getOauthCriConfigForConnection(MAIN_CONNECTION, HMRC_KBV_CRI))
                .thenReturn(hmrcKbvOauthCriConfig);
        when(configService.getSsmParameter(JWT_TTL_SECONDS)).thenReturn("900");
        when(configService.getSsmParameter(COMPONENT_ID)).thenReturn(IPV_ISSUER);
        when(configService.getComponentId(ADDRESS_CRI))
                .thenReturn(addressOauthCriConfig.getComponentId());
        when(configService.getAllowedSharedAttributes(HMRC_KBV_CRI))
                .thenReturn("name,birthDate,address,socialSecurityRecord");
        when(mockIpvSessionService.getIpvSession(SESSION_ID)).thenReturn(ipvSessionItem);
        mockVcHelper.when(() -> VcHelper.isSuccessfulVc(any())).thenReturn(true, true);
        when(mockSessionCredentialService.getCredentials(SESSION_ID, TEST_USER_ID))
                .thenReturn(
                        List.of(
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_1),
                                        IPV_ISSUER),
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_2),
                                        IPV_ISSUER),
                                generateVerifiableCredential(
                                        TEST_USER_ID,
                                        ADDRESS_CRI,
                                        vcClaim(CREDENTIAL_ATTRIBUTES_3),
                                        ADDRESS_ISSUER)));
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockKmsEs256SignerFactory.getSigner(any()))
                .thenReturn(new ECDSASigner(getSigningPrivateKey()));

        JourneyRequest input =
                JourneyRequest.builder()
                        .ipvSessionId(SESSION_ID)
                        .ipAddress(TEST_IP_ADDRESS)
                        .journey(String.format(JOURNEY_BASE_URL, HMRC_KBV_CRI))
                        .build();

        // Act
        var responseJson = handleRequest(input, context);

        // Assert
        CriResponse response = OBJECT_MAPPER.readValue(responseJson, CriResponse.class);

        URIBuilder redirectUri = new URIBuilder(response.getCri().getRedirectUrl());
        List<NameValuePair> queryParams = redirectUri.getQueryParams();

        Optional<NameValuePair> request =
                queryParams.stream().filter(param -> param.getName().equals("request")).findFirst();

        assertTrue(request.isPresent());
        JWEObject jweObject = JWEObject.parse(request.get().getValue());
        jweObject.decrypt(new RSADecrypter(getEncryptionPrivateKey()));

        SignedJWT signedJWT = SignedJWT.parse(jweObject.getPayload().toString());
        JsonNode claimsSet = OBJECT_MAPPER.readTree(signedJWT.getJWTClaimsSet().toString());

        JsonNode sharedClaims = claimsSet.get(TEST_SHARED_CLAIMS);
        assertEquals(1, sharedClaims.get("socialSecurityRecord").size());
        assertEquals(
                TEST_NI_NUMBER,
                sharedClaims.get("socialSecurityRecord").get(0).get("personalNumber").asText());
        verify(mockSessionCredentialService).getCredentials(SESSION_ID, TEST_USER_ID);
    }

    @Test
    void shouldReceive500CredentialOfUnknownType() throws Exception {
        // Arrange
        when(configService.getActiveConnection(F2F_CRI)).thenReturn(MAIN_CONNECTION);
        when(configService.getOauthCriConfigForConnection(MAIN_CONNECTION, F2F_CRI))
                .thenReturn(dcmawOauthCriConfig);
        when(configService.getSsmParameter(COMPONENT_ID)).thenReturn(IPV_ISSUER);
        when(mockIpvSessionService.getIpvSession(SESSION_ID)).thenReturn(ipvSessionItem);
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockGpg45ProfileEvaluator.buildScore(any()))
                .thenThrow(new UnknownEvidenceTypeException());

        JourneyRequest input =
                JourneyRequest.builder()
                        .ipvSessionId(SESSION_ID)
                        .ipAddress(TEST_IP_ADDRESS)
                        .journey(String.format(JOURNEY_BASE_URL, F2F_CRI))
                        .build();

        // Act
        var response = handleRequest(input, context);

        // Assert
        assertErrorResponse(
                HttpStatus.SC_INTERNAL_SERVER_ERROR,
                response,
                ErrorResponse.FAILED_TO_DETERMINE_CREDENTIAL_TYPE);
    }

    @ParameterizedTest
    @MethodSource("journeyUriParameters")
    void shouldIncludeGivenParametersIntoCriResponseIfInJourneyUri(
            String journeyUri, Map<String, String> expectedClaims) throws Exception {
        // Arrange
        when(configService.getActiveConnection(CLAIMED_IDENTITY_CRI)).thenReturn(MAIN_CONNECTION);
        when(configService.getOauthCriConfigForConnection(MAIN_CONNECTION, CLAIMED_IDENTITY_CRI))
                .thenReturn(claimedIdentityOauthCriConfig);
        when(configService.getSsmParameter(COMPONENT_ID)).thenReturn(IPV_ISSUER);
        when(configService.getSsmParameter(JWT_TTL_SECONDS)).thenReturn("5000");
        when(mockIpvSessionService.getIpvSession(SESSION_ID)).thenReturn(ipvSessionItem);
        when(mockClientOAuthSessionDetailsService.getClientOAuthSession(any()))
                .thenReturn(clientOAuthSessionItem);
        when(mockKmsEs256SignerFactory.getSigner(any()))
                .thenReturn(new ECDSASigner(getSigningPrivateKey()));

        JourneyRequest input =
                JourneyRequest.builder()
                        .ipvSessionId(SESSION_ID)
                        .ipAddress(TEST_IP_ADDRESS)
                        .journey(String.format(JOURNEY_BASE_URL, journeyUri))
                        .build();

        // Act
        var responseJson = handleRequest(input, context);

        // Assert
        CriResponse response = OBJECT_MAPPER.readValue(responseJson, CriResponse.class);

        URIBuilder redirectUri = new URIBuilder(response.getCri().getRedirectUrl());
        List<NameValuePair> queryParams = redirectUri.getQueryParams();

        Optional<NameValuePair> request =
                queryParams.stream().filter(param -> param.getName().equals("request")).findFirst();

        assertTrue(request.isPresent(), "Expected request parameter to be present");
        JWEObject jweObject = JWEObject.parse(request.get().getValue());
        jweObject.decrypt(new RSADecrypter(getEncryptionPrivateKey()));

        SignedJWT signedJWT = SignedJWT.parse(jweObject.getPayload().toString());
        JsonNode claimsSet = OBJECT_MAPPER.readTree(signedJWT.getJWTClaimsSet().toString());

        for (Map.Entry<String, String> entry : expectedClaims.entrySet()) {
            assertEquals(
                    entry.getValue(),
                    claimsSet.get(entry.getKey()).asText(),
                    () ->
                            String.format(
                                    "Expected claim for key=%s to be %s, but found %s",
                                    entry.getKey(),
                                    entry.getValue(),
                                    claimsSet.get(entry.getKey()).asText()));
        }
    }

    private static Stream<Arguments> journeyUriParameters() {
        return Stream.of(
                Arguments.of(CRI_WITH_CONTEXT, Map.of(CONTEXT, TEST_CONTEXT)),
                Arguments.of(CRI_WITH_SCOPE, Map.of(SCOPE, TEST_SCOPE)),
                Arguments.of(
                        CRI_WITH_CONTEXT_AND_SCOPE,
                        Map.of(CONTEXT, TEST_CONTEXT, SCOPE, TEST_SCOPE)));
    }

    private void assertErrorResponse(
            int httpStatusCode, String responseJson, ErrorResponse errorResponse)
            throws JsonProcessingException {
        JourneyErrorResponse response =
                OBJECT_MAPPER.readValue(responseJson, JourneyErrorResponse.class);
        assertEquals(httpStatusCode, response.getStatusCode());
        assertEquals(errorResponse.getCode(), response.getCode());
        assertEquals(errorResponse.getMessage(), response.getMessage());
    }

    private ECPrivateKey getSigningPrivateKey()
            throws InvalidKeySpecException, NoSuchAlgorithmException {
        return (ECPrivateKey)
                KeyFactory.getInstance("EC")
                        .generatePrivate(
                                new PKCS8EncodedKeySpec(
                                        Base64.getDecoder().decode(EC_PRIVATE_KEY)));
    }

    private PrivateKey getEncryptionPrivateKey()
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        return KeyFactory.getInstance("RSA")
                .generatePrivate(
                        new PKCS8EncodedKeySpec(
                                Base64.getDecoder().decode(RSA_ENCRYPTION_PRIVATE_KEY)));
    }

    private static String getJsonResponse(Map<String, Object> response)
            throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsString(response);
    }

    private String handleRequest(JourneyRequest event, Context context)
            throws JsonProcessingException {
        final var response = buildCriOauthRequestHandler.handleRequest(event, context);
        return getJsonResponse(OBJECT_MAPPER.convertValue(response, new TypeReference<>() {}));
    }
}
