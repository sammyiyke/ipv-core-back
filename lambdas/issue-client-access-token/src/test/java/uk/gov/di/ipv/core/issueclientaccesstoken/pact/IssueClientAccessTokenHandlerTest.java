package uk.gov.di.ipv.core.issueclientaccesstoken.pact;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import com.nimbusds.jose.JOSEException;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uk.gov.di.ipv.core.issueclientaccesstoken.IssueClientAccessTokenHandler;
import uk.gov.di.ipv.core.issueclientaccesstoken.persistance.item.ClientAuthJwtIdItem;
import uk.gov.di.ipv.core.issueclientaccesstoken.service.AccessTokenService;
import uk.gov.di.ipv.core.issueclientaccesstoken.service.ClientAuthJwtIdService;
import uk.gov.di.ipv.core.issueclientaccesstoken.validation.TokenRequestValidator;
import uk.gov.di.ipv.core.library.config.ConfigurationVariable;
import uk.gov.di.ipv.core.library.dto.AuthorizationCodeMetadata;
import uk.gov.di.ipv.core.library.pacttesthelpers.LambdaHttpServer;
import uk.gov.di.ipv.core.library.persistence.DataStore;
import uk.gov.di.ipv.core.library.persistence.item.ClientOAuthSessionItem;
import uk.gov.di.ipv.core.library.persistence.item.IpvSessionItem;
import uk.gov.di.ipv.core.library.service.ClientOAuthSessionDetailsService;
import uk.gov.di.ipv.core.library.service.ConfigService;
import uk.gov.di.ipv.core.library.service.IpvSessionService;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

import static org.mockito.Mockito.when;
import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.COMPONENT_ID;
import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.MAX_ALLOWED_AUTH_CLIENT_TTL;
import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.PUBLIC_KEY_MATERIAL_FOR_CORE_TO_VERIFY;

@PactFolder("pacts")
@Disabled("PACT tests should not be run in build pipelines at this time")
@Provider("IpvCoreBackTokenProvider")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IssueClientAccessTokenHandlerTest {

    private static final int PORT = 5050;

    private LambdaHttpServer httpServer;
    private IpvSessionItem ipvSessionItem;
    @Mock private ConfigService configService;
    @Mock private DataStore<IpvSessionItem> ipvSessionDataStore;
    @Mock private DataStore<ClientOAuthSessionItem> oAuthDataStore;
    @Mock private DataStore<ClientAuthJwtIdItem> jwtIdStore;

    @BeforeAll
    static void setupServer() {
        System.setProperty("pact.verifier.publishResults", "true");
        System.setProperty("pact.content_type.override.application/jwt", "text");
    }

    @BeforeEach
    void pactSetup(PactVerificationContext context)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException, JOSEException {

        var accessTokenService = new AccessTokenService(configService);
        var sessionService = new IpvSessionService(ipvSessionDataStore, configService);
        var clientOAuthSessionService =
                new ClientOAuthSessionDetailsService(oAuthDataStore, configService);
        var clientAuthJwtIdService = new ClientAuthJwtIdService(configService, jwtIdStore);
        var tokenRequestValidator =
                new TokenRequestValidator(configService, clientAuthJwtIdService);
        ipvSessionItem = new IpvSessionItem();
        var clientOAuthSessionItem = new ClientOAuthSessionItem();
        var authorizationCodeMetadata = new AuthorizationCodeMetadata();
        authorizationCodeMetadata.setCreationDateTime(
                "2024-02-01T00:00:00.000Z"); // Ensure that the metadata isn't flagged as expired

        when(configService.getSsmParameter(MAX_ALLOWED_AUTH_CLIENT_TTL))
                .thenReturn("3153600000"); // 100 years
        when(configService.getSsmParameter(ConfigurationVariable.AUTH_CODE_EXPIRY_SECONDS))
                .thenReturn("3153600000"); // 100 years
        when(configService.getBearerAccessTokenTtl()).thenReturn(3153600000L); // 100 years
        ipvSessionItem.setClientOAuthSessionId("dummyOuthSessionId");
        when(oAuthDataStore.getItem("dummyOuthSessionId")).thenReturn(clientOAuthSessionItem);
        ipvSessionItem.setAuthorizationCodeMetadata(authorizationCodeMetadata);

        // Set up the web server for the tests
        var handler =
                new IssueClientAccessTokenHandler(
                        accessTokenService,
                        sessionService,
                        configService,
                        clientOAuthSessionService,
                        tokenRequestValidator);

        httpServer = new LambdaHttpServer(handler, "/token", PORT);
        httpServer.startServer();

        context.setTarget(new HttpTestTarget("localhost", PORT));
    }

    @AfterEach
    public void tearDown() {
        httpServer.stopServer();
    }

    @State("dummyAuthCode is a valid authorization code")
    public void setAuthCode() {
        when(ipvSessionDataStore.getItemByIndex(
                        "authorizationCode",
                        DigestUtils.sha256Hex(
                                "dummyAuthCode"))) // 56298e46fe43e76f556b5aaea8601d758dd47c084495bf197b985a4e516ac5ce
                .thenReturn(ipvSessionItem);
    }

    @State(
            "the JWT is signed with {\"kty\":\"EC\",\"d\":\"A2cfN3vYKgOQ_r1S6PhGHCLLiVEqUshFYExrxMwkq_A\",\"crv\":\"P-256\",\"kid\":\"14342354354353\",\"x\":\"BMyQQqr3NEFYgb9sEo4hRBje_HHEsy87PbNIBGL4Uiw\",\"y\":\"qoXdkYVomy6HWT6yNLqjHSmYoICs6ioUF565Btx0apw\",\"alg\":\"ES256\"}")
    public void setSigningKey() {
        var signingKey =
                """
                {"kty":"EC","d":"A2cfN3vYKgOQ_r1S6PhGHCLLiVEqUshFYExrxMwkq_A","crv":"P-256","kid":"14342354354353","x":"BMyQQqr3NEFYgb9sEo4hRBje_HHEsy87PbNIBGL4Uiw","y":"qoXdkYVomy6HWT6yNLqjHSmYoICs6ioUF565Btx0apw","alg":"ES256"}
                """;

        when(configService.getSsmParameter(
                        PUBLIC_KEY_MATERIAL_FOR_CORE_TO_VERIFY, "authOrchestrator"))
                .thenReturn(signingKey);
    }

    @State("dummyInvalidAuthCode is a invalid authorization code")
    public void dontSetAuthCode() {}

    @State("the audience is http://ipv/")
    public void setAudience() {
        when(configService.getSsmParameter(COMPONENT_ID)).thenReturn("http://ipv/");
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void testMethod(PactVerificationContext context) {
        context.verifyInteraction();
    }
}
