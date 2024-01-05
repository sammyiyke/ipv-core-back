package uk.gov.di.ipv.core.library.verifiablecredential.validator;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.impl.ECDSA;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.di.ipv.core.library.domain.ContraIndicatorConfig;
import uk.gov.di.ipv.core.library.domain.ErrorResponse;
import uk.gov.di.ipv.core.library.dto.OauthCriConfig;
import uk.gov.di.ipv.core.library.exceptions.VerifiableCredentialException;
import uk.gov.di.ipv.core.library.service.ConfigService;

import java.text.ParseException;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.CREDENTIAL_ATTRIBUTES_2;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.EC_PRIVATE_KEY_JWK;
import static uk.gov.di.ipv.core.library.helpers.VerifiableCredentialGenerator.generateVerifiableCredential;
import static uk.gov.di.ipv.core.library.helpers.VerifiableCredentialGenerator.vcClaim;

@ExtendWith(MockitoExtension.class)
class VerifiableCredentialJwtValidatorTest {
    private static final String TEST_USER = "urn:uuid:596f44ec-5c53-4965-9ef4-e8200e39cf35";
    private static final String TEST_ISSUER =
            "https://staging-di-ipv-cri-address-front.london.cloudapps.digital";
    private static final ECKey TEST_SIGNING_KEY;
    private static final ECKey TEST_SIGNING_KEY2;
    private static final Map<String, ContraIndicatorConfig> CI_MAP =
            Map.of("A02", new ContraIndicatorConfig(), "A03", new ContraIndicatorConfig());

    static {
        try {
            TEST_SIGNING_KEY = ECKey.parse(EC_PRIVATE_KEY_JWK);
            TEST_SIGNING_KEY2 =
                    ECKey.parse(
                            "{\"crv\":\"P-256\",\"d\":\"o1orSH_mS3u1zzi4wXa9C-cgY2bPyZWN5DxK78JCN6E\",\"kty\":\"EC\",\"x\":\"LziA3lV476BwPG5glvLLx8-FzMbeX2ti9wYlhwCWNhQ\",\"y\":\"NfvgSlu1TMNjjMRM3um29Tv79C4NL8x6WEY7t4BBneA\"}");
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    @Mock private OauthCriConfig oauthCriConfig;
    @Mock private ConfigService mockConfigService;
    private SignedJWT verifiableCredentials;

    private VerifiableCredentialJwtValidator vcJwtValidator;

    @BeforeEach
    void setUp() throws Exception {
        verifiableCredentials = createTestVerifiableCredentials(TEST_USER, TEST_ISSUER);
        vcJwtValidator = new VerifiableCredentialJwtValidator(mockConfigService);
    }

    @Test
    void validatesValidVerifiableCredentialsSuccessfully() throws Exception {
        when(mockConfigService.getContraIndicatorConfigMap()).thenReturn(CI_MAP);

        setCredentialIssuerConfigMockResponses(TEST_SIGNING_KEY);
        vcJwtValidator.validate(verifiableCredentials, oauthCriConfig, TEST_USER);
    }

    @Test
    void validatesThrowParseException() throws ParseException {
        when(oauthCriConfig.getSigningKey()).thenThrow(new ParseException("Whoops", 0));
        var exception =
                assertThrows(
                        VerifiableCredentialException.class,
                        () -> {
                            vcJwtValidator.validate(
                                    verifiableCredentials, oauthCriConfig, TEST_USER);
                        });
        assertEquals(HTTPResponse.SC_SERVER_ERROR, exception.getHttpStatusCode());
        assertEquals(ErrorResponse.FAILED_TO_PARSE_JWK, exception.getErrorResponse());
    }

    @Test
    void validateThrowsErrorOnInvalidVerifiableCredentials() {
        setCredentialIssuerConfigMockResponses(TEST_SIGNING_KEY);
        var exception =
                assertThrows(
                        VerifiableCredentialException.class,
                        () -> {
                            vcJwtValidator.validate(
                                    verifiableCredentials, oauthCriConfig, "a different user");
                        });
        assertEquals(HTTPResponse.SC_SERVER_ERROR, exception.getHttpStatusCode());
        assertEquals(
                ErrorResponse.FAILED_TO_VALIDATE_VERIFIABLE_CREDENTIAL,
                exception.getErrorResponse());
    }

    @Test
    void validateThrowsErrorOnInvalidVerifiableCredentialsSignature() {
        try {
            when(oauthCriConfig.getSigningKey()).thenReturn(TEST_SIGNING_KEY2);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
        var exception =
                assertThrows(
                        VerifiableCredentialException.class,
                        () -> {
                            vcJwtValidator.validate(
                                    verifiableCredentials, oauthCriConfig, TEST_USER);
                        });
        assertEquals(HTTPResponse.SC_SERVER_ERROR, exception.getHttpStatusCode());
        assertEquals(
                ErrorResponse.FAILED_TO_VALIDATE_VERIFIABLE_CREDENTIAL,
                exception.getErrorResponse());
    }

    @Test
    void validatesValidVerifiableCredentialsWithDerSignatureSuccessfully() throws Exception {
        when(mockConfigService.getContraIndicatorConfigMap()).thenReturn(CI_MAP);

        setCredentialIssuerConfigMockResponses(TEST_SIGNING_KEY);
        var jwtParts = verifiableCredentials.getParsedParts();
        var verifiableCredentialsWithDerSignature =
                new SignedJWT(
                        jwtParts[0],
                        jwtParts[1],
                        Base64URL.encode(
                                ECDSA.transcodeSignatureToDER(
                                        verifiableCredentials.getSignature().decode())));
        vcJwtValidator.validate(verifiableCredentialsWithDerSignature, oauthCriConfig, TEST_USER);
    }

    @Test
    void throwsErrorOnVerifiableCredentialsWithInvalidDerSignature()
            throws JOSEException, ParseException {
        var derSignature =
                ECDSA.transcodeSignatureToDER(verifiableCredentials.getSignature().decode());
        var jwtParts = verifiableCredentials.getParsedParts();
        var verifiableCredentialsWithDerSignature =
                new SignedJWT(
                        jwtParts[0],
                        jwtParts[1],
                        Base64URL.encode(
                                Arrays.copyOfRange(derSignature, 1, derSignature.length - 2)));
        var exception =
                assertThrows(
                        VerifiableCredentialException.class,
                        () -> {
                            vcJwtValidator.validate(
                                    verifiableCredentialsWithDerSignature,
                                    oauthCriConfig,
                                    TEST_USER);
                        });
        assertEquals(HTTPResponse.SC_SERVER_ERROR, exception.getHttpStatusCode());
        assertEquals(
                ErrorResponse.FAILED_TO_VALIDATE_VERIFIABLE_CREDENTIAL,
                exception.getErrorResponse());
    }

    @Test
    void validatesValidVCSuccessfully() throws Exception {
        when(mockConfigService.getContraIndicatorConfigMap()).thenReturn(CI_MAP);

        setCredentialIssuerConfigMockResponses(TEST_SIGNING_KEY);
        vcJwtValidator.validate(
                verifiableCredentials,
                oauthCriConfig.getSigningKey(),
                oauthCriConfig.getComponentId(),
                TEST_USER);
    }

    @Test
    void validateThrowsIfCiCodesAreNotRecognised() throws Exception {
        when(mockConfigService.getContraIndicatorConfigMap())
                .thenReturn(Map.of("NO", new ContraIndicatorConfig()));
        setCredentialIssuerConfigMockResponses(TEST_SIGNING_KEY);
        ECKey signingKey = oauthCriConfig.getSigningKey();
        String componentId = oauthCriConfig.getComponentId();

        VerifiableCredentialException exception =
                assertThrows(
                        VerifiableCredentialException.class,
                        () -> {
                            vcJwtValidator.validate(
                                    verifiableCredentials, signingKey, componentId, TEST_USER);
                        });

        assertEquals(HTTPResponse.SC_SERVER_ERROR, exception.getHttpStatusCode());
        assertEquals(
                ErrorResponse.FAILED_TO_VALIDATE_VERIFIABLE_CREDENTIAL,
                exception.getErrorResponse());
    }

    private void setCredentialIssuerConfigMockResponses(ECKey signingKey) {
        when(oauthCriConfig.getComponentId())
                .thenReturn("https://staging-di-ipv-cri-address-front.london.cloudapps.digital");
        try {
            when(oauthCriConfig.getSigningKey()).thenReturn(signingKey);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    private SignedJWT createTestVerifiableCredentials(String subject, String issuer)
            throws Exception {
        return SignedJWT.parse(
                generateVerifiableCredential(vcClaim(CREDENTIAL_ATTRIBUTES_2), subject, issuer));
    }
}
