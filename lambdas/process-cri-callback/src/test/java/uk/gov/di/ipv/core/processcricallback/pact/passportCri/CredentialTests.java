package uk.gov.di.ipv.core.processcricallback.pact.passportCri;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit.MockServerConfig;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.di.ipv.core.library.domain.ContraIndicatorConfig;
import uk.gov.di.ipv.core.library.domain.ErrorResponse;
import uk.gov.di.ipv.core.library.domain.VerifiableCredentialConstants;
import uk.gov.di.ipv.core.library.dto.CriCallbackRequest;
import uk.gov.di.ipv.core.library.dto.OauthCriConfig;
import uk.gov.di.ipv.core.library.exceptions.VerifiableCredentialException;
import uk.gov.di.ipv.core.library.helpers.FixedTimeJWTClaimsVerifier;
import uk.gov.di.ipv.core.library.helpers.SecureTokenHelper;
import uk.gov.di.ipv.core.library.kmses256signer.KmsEs256SignerFactory;
import uk.gov.di.ipv.core.library.pacttesthelpers.PactJwtIgnoreSignatureBodyBuilder;
import uk.gov.di.ipv.core.library.persistence.item.CriOAuthSessionItem;
import uk.gov.di.ipv.core.library.service.ConfigService;
import uk.gov.di.ipv.core.library.verifiablecredential.validator.VerifiableCredentialValidator;
import uk.gov.di.ipv.core.processcricallback.exception.CriApiException;
import uk.gov.di.ipv.core.processcricallback.service.CriApiService;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Date;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static uk.gov.di.ipv.core.library.domain.CriConstants.PASSPORT_CRI;

@ExtendWith(PactConsumerTestExt.class)
@ExtendWith(MockitoExtension.class)
@PactTestFor(providerName = "PassportVcProvider")
@MockServerConfig(hostInterface = "localhost")
class CredentialTests {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    @Mock private ConfigService mockConfigService;
    @Mock private KmsEs256SignerFactory mockKmsEs256SignerFactory;
    @Mock private JWSSigner mockSigner;
    @Mock private SecureTokenHelper mockSecureTokenHelper;

    @Pact(provider = "PassportVcProvider", consumer = "IpvCoreBack")
    public RequestResponsePact validRequestReturnsValidCredential(PactDslWithProvider builder)
            throws Exception {
        return builder.given("dummyApiKey is a valid api key")
                .given("dummyAccessToken is a valid access token")
                .given("test-subject is a valid subject")
                .given("dummyPassportComponentId is a valid issuer")
                .given("VC givenName is Mary")
                .given("VC familyName is Watson")
                .given("VC birthDate is 1932-02-25")
                .given("VC passport documentNumber is 824159121")
                .given("VC passport expiryDate is 2030-01-01")
                .uponReceiving("Valid credential request for VC")
                .path("/issue/credential")
                .method("POST")
                .headers("x-api-key", PRIVATE_API_KEY, "Authorization", "Bearer dummyAccessToken")
                .willRespondWith()
                .status(200)
                .body(
                        new PactJwtIgnoreSignatureBodyBuilder(
                                VALID_VC_HEADER, VALID_VC_BODY, VALID_VC_SIGNATURE))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "validRequestReturnsValidCredential")
    void fetchVerifiableCredential_whenCalledAgainstPassportCri_retrievesAValidVc(
            MockServer mockServer) throws URISyntaxException, CriApiException {
        // Arrange
        var credentialIssuerConfig = getMockCredentialIssuerConfig(mockServer);
        configureMockConfigService(credentialIssuerConfig);

        // We need to generate a fixed request, so we set the secure token and expiry to constant
        // values.
        var underTest =
                new CriApiService(
                        mockConfigService,
                        mockKmsEs256SignerFactory,
                        mockSecureTokenHelper,
                        CURRENT_TIME);

        // Act
        var verifiableCredentialResponse =
                underTest.fetchVerifiableCredential(
                        new BearerAccessToken("dummyAccessToken"),
                        getCallbackRequest("dummyAuthCode", credentialIssuerConfig),
                        CRI_OAUTH_SESSION_ITEM);

        // Assert
        var verifiableCredentialJwtValidator = getVerifiableCredentialJwtValidator();
        verifiableCredentialResponse
                .getVerifiableCredentials()
                .forEach(
                        credential -> {
                            try {
                                var vc =
                                        verifiableCredentialJwtValidator.parseAndValidate(
                                                TEST_USER,
                                                PASSPORT_CRI,
                                                credential,
                                                VerifiableCredentialConstants
                                                        .IDENTITY_CHECK_CREDENTIAL_TYPE,
                                                ECKey.parse(CRI_SIGNING_PRIVATE_KEY_JWK),
                                                TEST_ISSUER,
                                                false);

                                JsonNode credentialSubject =
                                        objectMapper
                                                .readTree(vc.getClaimsSet().toString())
                                                .get("vc")
                                                .get("credentialSubject");

                                JsonNode nameParts =
                                        credentialSubject.get("name").get(0).get("nameParts");
                                JsonNode birthDateNode = credentialSubject.get("birthDate").get(0);
                                JsonNode passportNode = credentialSubject.get("passport").get(0);

                                assertEquals("GivenName", nameParts.get(0).get("type").asText());
                                assertEquals("FamilyName", nameParts.get(1).get("type").asText());
                                assertEquals("Mary", nameParts.get(0).get("value").asText());
                                assertEquals("Watson", nameParts.get(1).get("value").asText());

                                assertEquals("2030-01-01", passportNode.get("expiryDate").asText());
                                assertEquals(
                                        "824159121", passportNode.get("documentNumber").asText());

                                assertEquals("1932-02-25", birthDateNode.get("value").asText());
                            } catch (VerifiableCredentialException
                                    | ParseException
                                    | JsonProcessingException e) {
                                throw new RuntimeException(e);
                            }
                        });
    }

    @Pact(provider = "PassportVcProvider", consumer = "IpvCoreBack")
    public RequestResponsePact validRequestReturnsFailedCredentialWithCi(
            PactDslWithProvider builder) throws Exception {
        return builder.given("dummyApiKey is a valid api key")
                .given("dummyAccessToken is a valid access token")
                .given("test-subject is a valid subject")
                .given("dummyPassportComponentId is a valid issuer")
                .given("VC givenName is Mary")
                .given("VC familyName is Watson")
                .given("VC birthDate is 1932-02-25")
                .given("VC passport documentNumber is 123456789")
                .given("VC passport expiryDate is 2030-12-12")
                .uponReceiving("Valid credential request for VC with CI")
                .path("/issue/credential")
                .method("POST")
                .headers("x-api-key", PRIVATE_API_KEY, "Authorization", "Bearer dummyAccessToken")
                .willRespondWith()
                .body(
                        new PactJwtIgnoreSignatureBodyBuilder(
                                VALID_VC_HEADER, FAILED_VC_BODY, FAILED_VC_SIGNATURE))
                .status(200)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "validRequestReturnsFailedCredentialWithCi")
    void fetchVerifiableCredential_whenCalledAgainstPassportCri_retrievesAValidVcWithACi(
            MockServer mockServer) throws URISyntaxException, CriApiException {
        // Arrange
        var credentialIssuerConfig = getMockCredentialIssuerConfig(mockServer);
        configureMockConfigService(credentialIssuerConfig);

        // We need to generate a fixed request, so we set the secure token and expiry to constant
        // values.
        var underTest =
                new CriApiService(
                        mockConfigService,
                        mockKmsEs256SignerFactory,
                        mockSecureTokenHelper,
                        CURRENT_TIME);

        // Act
        var verifiableCredentialResponse =
                underTest.fetchVerifiableCredential(
                        new BearerAccessToken("dummyAccessToken"),
                        getCallbackRequest("dummyAuthCode", credentialIssuerConfig),
                        CRI_OAUTH_SESSION_ITEM);

        // Assert
        var verifiableCredentialJwtValidator = getVerifiableCredentialJwtValidator();
        verifiableCredentialResponse
                .getVerifiableCredentials()
                .forEach(
                        credential -> {
                            try {
                                var vc =
                                        verifiableCredentialJwtValidator.parseAndValidate(
                                                TEST_USER,
                                                PASSPORT_CRI,
                                                credential,
                                                VerifiableCredentialConstants
                                                        .IDENTITY_CHECK_CREDENTIAL_TYPE,
                                                ECKey.parse(CRI_SIGNING_PRIVATE_KEY_JWK),
                                                TEST_ISSUER,
                                                false);

                                JsonNode vcClaim =
                                        objectMapper
                                                .readTree(vc.getClaimsSet().toString())
                                                .get("vc");

                                JsonNode credentialSubject = vcClaim.get("credentialSubject");

                                JsonNode nameParts =
                                        credentialSubject.get("name").get(0).get("nameParts");
                                JsonNode birthDateNode = credentialSubject.get("birthDate").get(0);
                                JsonNode passportNode = credentialSubject.get("passport").get(0);
                                JsonNode evidence = vcClaim.get("evidence").get(0);
                                JsonNode ciNode = evidence.get("ci");

                                // Assert
                                assertEquals("GivenName", nameParts.get(0).get("type").asText());
                                assertEquals("FamilyName", nameParts.get(1).get("type").asText());
                                assertEquals("Mary", nameParts.get(0).get("value").asText());
                                assertEquals("Watson", nameParts.get(1).get("value").asText());
                                assertEquals("D02", ciNode.get(0).asText());

                                assertEquals("2030-01-01", passportNode.get("expiryDate").asText());
                                assertEquals(
                                        "123456789", passportNode.get("documentNumber").asText());

                                assertEquals("1932-02-25", birthDateNode.get("value").asText());
                            } catch (VerifiableCredentialException
                                    | ParseException
                                    | JsonProcessingException e) {
                                throw new RuntimeException(e);
                            }
                        });
    }

    @Pact(provider = "PassportVcProvider", consumer = "IpvCoreBack")
    public RequestResponsePact validRequestReturnsFailedCredentialWithScenario2Ci(
            PactDslWithProvider builder) throws Exception {
        return builder.given("dummyApiKey is a valid api key")
                .given("dummyAccessToken is a valid access token")
                .given("test-subject is a valid subject")
                .given("dummyPassportComponentId is a valid issuer")
                .given("VC givenName is Mary")
                .given("VC familyName is Watson")
                .given("VC birthDate is 1932-02-25")
                .given("VC passport documentNumber is 123456789")
                .given("VC passport expiryDate is 2030-12-12")
                .given("VC is a scenario 2 failure")
                .uponReceiving("Valid credential request for VC with scenario 2 CI")
                .path("/issue/credential")
                .method("POST")
                .headers("x-api-key", PRIVATE_API_KEY, "Authorization", "Bearer dummyAccessToken")
                .willRespondWith()
                .body(
                        new PactJwtIgnoreSignatureBodyBuilder(
                                VALID_VC_HEADER,
                                FAILED_VC_SCENARIO_2_BODY,
                                FAILED_VC_SCENARIO_2_SIGNATURE))
                .status(200)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "validRequestReturnsFailedCredentialWithScenario2Ci")
    void fetchVerifiableCredential_whenCalledAgainstPassportCri_retrievesAValidVcWithAScenario2Ci(
            MockServer mockServer) throws URISyntaxException, CriApiException {
        // Arrange
        var credentialIssuerConfig = getMockCredentialIssuerConfig(mockServer);
        configureMockConfigService(credentialIssuerConfig);

        // We need to generate a fixed request, so we set the secure token and expiry to constant
        // values.
        var underTest =
                new CriApiService(
                        mockConfigService,
                        mockKmsEs256SignerFactory,
                        mockSecureTokenHelper,
                        CURRENT_TIME);

        // Act
        var verifiableCredentialResponse =
                underTest.fetchVerifiableCredential(
                        new BearerAccessToken("dummyAccessToken"),
                        getCallbackRequest("dummyAuthCode", credentialIssuerConfig),
                        CRI_OAUTH_SESSION_ITEM);

        // Assert
        var verifiableCredentialJwtValidator = getVerifiableCredentialJwtValidator();
        verifiableCredentialResponse
                .getVerifiableCredentials()
                .forEach(
                        credential -> {
                            try {
                                var vc =
                                        verifiableCredentialJwtValidator.parseAndValidate(
                                                TEST_USER,
                                                PASSPORT_CRI,
                                                credential,
                                                VerifiableCredentialConstants
                                                        .IDENTITY_CHECK_CREDENTIAL_TYPE,
                                                ECKey.parse(CRI_SIGNING_PRIVATE_KEY_JWK),
                                                TEST_ISSUER,
                                                false);

                                JsonNode vcClaim =
                                        objectMapper
                                                .readTree(vc.getClaimsSet().toString())
                                                .get("vc");

                                JsonNode credentialSubject = vcClaim.get("credentialSubject");

                                JsonNode nameParts =
                                        credentialSubject.get("name").get(0).get("nameParts");
                                JsonNode birthDateNode = credentialSubject.get("birthDate").get(0);
                                JsonNode passportNode = credentialSubject.get("passport").get(0);
                                JsonNode evidence = vcClaim.get("evidence").get(0);
                                JsonNode ciNode = evidence.get("ci");

                                // Assert
                                assertEquals("GivenName", nameParts.get(0).get("type").asText());
                                assertEquals("FamilyName", nameParts.get(1).get("type").asText());
                                assertEquals("Mary", nameParts.get(0).get("value").asText());
                                assertEquals("Watson", nameParts.get(1).get("value").asText());
                                assertEquals("CI01", ciNode.get(0).asText());

                                assertEquals("2030-01-01", passportNode.get("expiryDate").asText());
                                assertEquals(
                                        "123456789", passportNode.get("documentNumber").asText());

                                assertEquals("1932-02-25", birthDateNode.get("value").asText());
                            } catch (VerifiableCredentialException
                                    | ParseException
                                    | JsonProcessingException e) {
                                throw new RuntimeException(e);
                            }
                        });
    }

    @Pact(provider = "PassportVcProvider", consumer = "IpvCoreBack")
    public RequestResponsePact invalidAccessTokenReturns403(PactDslWithProvider builder) {
        return builder.given("dummyApiKey is a valid api key")
                .given("dummyInvalidAccessToken is an invalid access token")
                .given("test-subject is a valid subject")
                .given("dummyPassportComponentId is a valid issuer")
                .uponReceiving("Invalid credential request due to invalid access token")
                .path("/issue/credential")
                .method("POST")
                .headers(
                        "x-api-key",
                        PRIVATE_API_KEY,
                        "Authorization",
                        "Bearer dummyInvalidAccessToken")
                .willRespondWith()
                .status(403)
                .body(
                        newJsonBody(
                                        (body) -> {
                                            body.object(
                                                    "oauth_error",
                                                    (error) -> {
                                                        error.stringType("error");
                                                        error.stringType("error_description");
                                                    });
                                        })
                                .build())
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "invalidAccessTokenReturns403")
    void
            fetchVerifiableCredential_whenCalledAgainstPassportCriWithInvalidAuthCode_throwsAnException(
                    MockServer mockServer) throws URISyntaxException, CriApiException {
        // Arrange
        var credentialIssuerConfig = getMockCredentialIssuerConfig(mockServer);
        configureMockConfigService(credentialIssuerConfig);

        // We need to generate a fixed request, so we set the secure token and expiry to constant
        // values.
        var underTest =
                new CriApiService(
                        mockConfigService,
                        mockKmsEs256SignerFactory,
                        mockSecureTokenHelper,
                        CURRENT_TIME);

        // Act
        CriApiException exception =
                assertThrows(
                        CriApiException.class,
                        () ->
                                underTest.fetchVerifiableCredential(
                                        new BearerAccessToken("dummyInvalidAccessToken"),
                                        getCallbackRequest("dummyAuthCode", credentialIssuerConfig),
                                        CRI_OAUTH_SESSION_ITEM));

        // Assert
        assertThat(
                exception.getErrorResponse(),
                is(ErrorResponse.FAILED_TO_GET_CREDENTIAL_FROM_ISSUER));
        assertThat(exception.getHttpStatusCode(), is(HTTPResponse.SC_SERVER_ERROR));
    }

    @NotNull
    private VerifiableCredentialValidator getVerifiableCredentialJwtValidator() {
        return new VerifiableCredentialValidator(
                mockConfigService,
                ((exactMatchClaims, requiredClaims) ->
                        new FixedTimeJWTClaimsVerifier<>(
                                exactMatchClaims,
                                requiredClaims,
                                Date.from(CURRENT_TIME.instant()))));
    }

    @NotNull
    private static CriCallbackRequest getCallbackRequest(
            String authCode, OauthCriConfig credentialIssuerConfig) {
        return new CriCallbackRequest(
                authCode,
                credentialIssuerConfig.getClientId(),
                "dummySessionId",
                "https://identity.staging.account.gov.uk/credential-issuer/callback?id=ukPassport",
                "dummyState",
                null,
                null,
                "dummyIpAddress",
                List.of("dummyFeatureSet"));
    }

    private void configureMockConfigService(OauthCriConfig credentialIssuerConfig) {
        ContraIndicatorConfig ciConfig1 = new ContraIndicatorConfig(null, 4, null, null);
        ContraIndicatorConfig ciConfig2 = new ContraIndicatorConfig(null, 4, null, null);
        Map<String, ContraIndicatorConfig> ciConfigMap = new HashMap<>();
        ciConfigMap.put("D02", ciConfig1);
        ciConfigMap.put("CI01", ciConfig2);

        when(mockConfigService.getOauthCriConfig(any())).thenReturn(credentialIssuerConfig);
        when(mockConfigService.getCriPrivateApiKey(any())).thenReturn(PRIVATE_API_KEY);
        // This mock doesn't get reached in error cases, but it would be messy to explicitly not set
        // it
        Mockito.lenient()
                .when(mockConfigService.getContraIndicatorConfigMap())
                .thenReturn(ciConfigMap);
    }

    @NotNull
    private static OauthCriConfig getMockCredentialIssuerConfig(MockServer mockServer)
            throws URISyntaxException {
        return OauthCriConfig.builder()
                .tokenUrl(new URI("http://localhost:" + mockServer.getPort() + "/token"))
                .credentialUrl(
                        new URI("http://localhost:" + mockServer.getPort() + "/issue/credential"))
                .authorizeUrl(new URI("http://localhost:" + mockServer.getPort() + "/authorize"))
                .clientId(IPV_CORE_CLIENT_ID)
                .signingKey(CRI_SIGNING_PRIVATE_KEY_JWK)
                .encryptionKey(CRI_RSA_ENCRYPTION_PUBLIC_JWK)
                .componentId(CRI_COMPONENT_ID)
                .clientCallbackUrl(
                        URI.create(
                                "https://identity.staging.account.gov.uk/credential-issuer/callback?id=ukPassport"))
                .requiresApiKey(true)
                .requiresAdditionalEvidence(false)
                .build();
    }

    private static final String TEST_USER = "test-subject";
    private static final String TEST_ISSUER = "dummyPassportComponentId";
    private static final String IPV_CORE_CLIENT_ID = "ipv-core";
    private static final String PRIVATE_API_KEY = "dummyApiKey";
    public static final String CRI_COMPONENT_ID = "dummyPassportComponentId";
    private static final Clock CURRENT_TIME =
            Clock.fixed(Instant.parse("2099-01-01T00:00:00.00Z"), ZoneOffset.UTC);
    public static final CriOAuthSessionItem CRI_OAUTH_SESSION_ITEM =
            new CriOAuthSessionItem(
                    "dummySessionId", "dummyOAuthSessionId", "dummyCriId", "dummyConnection", 900);
    private static final String CRI_SIGNING_PRIVATE_KEY_JWK =
            """
            {"kty":"EC","d":"OXt0P05ZsQcK7eYusgIPsqZdaBCIJiW4imwUtnaAthU","crv":"P-256","x":"E9ZzuOoqcVU4pVB9rpmTzezjyOPRlOmPGJHKi8RSlIM","y":"KlTMZthHZUkYz5AleTQ8jff0TJiS3q2OB9L5Fw4xA04"}
            """;
    private static final String CRI_RSA_ENCRYPTION_PUBLIC_JWK =
            """
            {"kty":"RSA","e":"AQAB","n":"vyapkvJXLwpYRJjbkQD99V2gcPEUKrO3dwjcAA9TPkLucQEZvYZvb7-wfSHxlvJlJcdS20r5PKKmqdPeW3Y4ir3WsVVeiht2iOZUreUO5O3V3o7ImvEjPS_2_ZKMHCwUf51a6WGOaDjO87OX_bluV2dp01n-E3kiIl6RmWCVywjn13fX3jsX0LMCM_bt3HofJqiYhhNymEwh39oR_D7EE5sLUii2XvpTYPa6L_uPwdKa4vRl4h4owrWEJaJifMorGcvqhCK1JOHqgknN_3cb_ns9Px6ynQCeFXvBDJy4q71clkBq_EZs5227Y1S222wXIwUYN8w5YORQe3M-pCIh1Q"}
            """;

    // We hardcode the VC headers and bodies like this so that it is easy to update them from JSON
    // sent by the CRI team
    private static final String VALID_VC_HEADER =
            """
            {
              "alg": "ES256",
              "typ": "JWT"
            }
            """;
    // 2099-01-01 00:00:00 is 4070908800 in epoch seconds
    private static final String VALID_VC_BODY =
            """
            {
                "iss": "dummyPassportComponentId",
                "sub": "test-subject",
                "nbf": 4070908800,
                "vc": {
                    "type": [
                        "VerifiableCredential",
                        "IdentityCheckCredential"
                    ],
                    "credentialSubject": {
                        "birthDate": [
                            {
                                "value": "1932-02-25"
                            }
                        ],
                        "name": [
                            {
                                "nameParts": [
                                    {
                                        "type": "GivenName",
                                        "value": "Mary"
                                    },
                                    {
                                        "type": "FamilyName",
                                        "value": "Watson"
                                    }
                                ]
                            }
                        ],
                        "passport": [
                            {
                                "documentNumber": "824159121",
                                "icaoIssuerCode": "GBR",
                                "expiryDate": "2030-01-01"
                            }
                        ]
                    },
                    "evidence": [
                        {
                            "type": "IdentityCheck",
                            "txn": "278450f1-75f5-4d0d-9e8e-8bc37a07248d",
                            "strengthScore": 4,
                            "validityScore": 2,
                            "ci": [],
                            "checkDetails": [
                                {
                                    "checkMethod": "data",
                                    "dataCheck": "scenario_1"
                                },
                                {
                                    "checkMethod": "data",
                                    "dataCheck": "record_check"
                                }
                            ],
                            "ciReasons": []
                        }
                    ]
                },
                "jti": "urn:uuid:b07cc7e3-a2dc-4b17-9826-6907fcf4059a"
            }
            """;
    // If we generate the signature in code it will be different each time, so we need to generate a
    // valid signature (using https://jwt.io works well) and record it here so the PACT file doesn't
    // change each time we run the tests.
    private static final String VALID_VC_SIGNATURE =
            "ZmeS-B5HQkQBEOnRogwGVuYORA28YiriPbdeKeGUtwVJ4bmvOAZD5ePNVOKO6788N8TAuYCC1uofV0J1gr_e9g";

    private static final String FAILED_VC_BODY =
            """
            {
                "iss": "dummyPassportComponentId",
                "sub": "test-subject",
                "nbf": 4070908800,
                "vc": {
                    "type": [
                        "VerifiableCredential",
                        "IdentityCheckCredential"
                    ],
                    "credentialSubject": {
                        "birthDate": [
                            {
                                "value": "1932-02-25"
                            }
                        ],
                        "name": [
                            {
                                "nameParts": [
                                    {
                                        "type": "GivenName",
                                        "value": "Mary"
                                    },
                                    {
                                        "type": "FamilyName",
                                        "value": "Watson"
                                    }
                                ]
                            }
                        ],
                        "passport": [
                            {
                                "documentNumber": "123456789",
                                "icaoIssuerCode": "GBR",
                                "expiryDate": "2030-01-01"
                            }
                        ]
                    },
                    "evidence": [
                        {
                            "type": "IdentityCheck",
                            "txn": "278450f1-75f5-4d0d-9e8e-8bc37a07248d",
                            "strengthScore": 4,
                            "validityScore": 0,
                            "ci": [
                                "D02"
                            ],
                            "failedCheckDetails": [
                                {
                                    "checkMethod": "data",
                                    "dataCheck": "record_check"
                                }
                            ],
                            "ciReasons": [
                                {
                                    "ci": "D02",
                                    "reason": "NoMatchingRecord"
                                }
                            ]
                        }
                    ]
                },
                "jti": "urn:uuid:b07cc7e3-a2dc-4b17-9826-6907fcf4059a"
            }
            """;
    // If we generate the signature in code it will be different each time, so we need to generate a
    // valid signature (using https://jwt.io works well) and record it here so the PACT file doesn't
    // change each time we run the tests.
    private static final String FAILED_VC_SIGNATURE =
            "BqDbzSgjr-kMUEMgtaMJ1Cr3zypulFXwXL6wPsz8rvlqL32Y_I_KyEnyf1oKnSuQAfEQIOtgHazi1kMO5ZElNg";

    private static final String FAILED_VC_SCENARIO_2_BODY =
            """
            {
                "iss": "dummyPassportComponentId",
                "sub": "test-subject",
                "nbf": 4070908800,
                "vc": {
                    "type": [
                        "VerifiableCredential",
                        "IdentityCheckCredential"
                    ],
                    "credentialSubject": {
                        "birthDate": [
                            {
                                "value": "1932-02-25"
                            }
                        ],
                        "name": [
                            {
                                "nameParts": [
                                    {
                                        "type": "GivenName",
                                        "value": "Mary"
                                    },
                                    {
                                        "type": "FamilyName",
                                        "value": "Watson"
                                    }
                                ]
                            }
                        ],
                        "passport": [
                            {
                                "documentNumber": "123456789",
                                "icaoIssuerCode": "GBR",
                                "expiryDate": "2030-01-01"
                            }
                        ]
                    },
                    "evidence": [
                        {
                            "type": "IdentityCheck",
                            "txn": "278450f1-75f5-4d0d-9e8e-8bc37a07248d",
                            "strengthScore": 4,
                            "validityScore": 0,
                            "ci": [
                                "CI01"
                            ],
                            "checkDetails": [
                                {
                                    "checkMethod": "data",
                                    "dataCheck": "record_check"
                                }
                            ],
                            "failedCheckDetails": [
                                {
                                    "checkMethod": "data",
                                    "dataCheck": "scenario1_check"
                                },
                                {
                                    "checkMethod": "data",
                                    "dataCheck": "scenario2_check"
                                }
                            ],
                            "ciReasons": [
                                {
                                    "ci": "CI01",
                                    "reason": "Scenario2"
                                }
                            ]
                        }
                    ]
                },
                "jti": "urn:uuid:b07cc7e3-a2dc-4b17-9826-6907fcf4059a"
            }
            """;
    // If we generate the signature in code it will be different each time, so we need to generate a
    // valid signature (using https://jwt.io works well) and record it here so the PACT file doesn't
    // change each time we run the tests.
    private static final String FAILED_VC_SCENARIO_2_SIGNATURE =
            "Nhtx_3xy_cjZCCA_rpdVTSg6WjutpPdZ0_BxBQrAx_hAy6Wr86H22iKL4O-B0dQ4z9-hzJOq3Y90IKp5pQNqLg";
}
