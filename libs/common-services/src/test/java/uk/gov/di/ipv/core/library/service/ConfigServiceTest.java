package uk.gov.di.ipv.core.library.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.secretsmanager.model.DecryptionFailureException;
import software.amazon.awssdk.services.secretsmanager.model.InternalServiceErrorException;
import software.amazon.awssdk.services.secretsmanager.model.InvalidParameterException;
import software.amazon.awssdk.services.secretsmanager.model.InvalidRequestException;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;
import software.amazon.lambda.powertools.parameters.SSMProvider;
import software.amazon.lambda.powertools.parameters.SecretsProvider;
import uk.gov.di.ipv.core.library.config.ConfigurationVariable;
import uk.gov.di.ipv.core.library.config.FeatureFlag;
import uk.gov.di.ipv.core.library.domain.ContraIndicatorConfig;
import uk.gov.di.ipv.core.library.domain.MitigationRoute;
import uk.gov.di.ipv.core.library.dto.CriConfig;
import uk.gov.di.ipv.core.library.dto.OauthCriConfig;
import uk.gov.di.ipv.core.library.dto.RestCriConfig;
import uk.gov.di.ipv.core.library.exceptions.ConfigException;
import uk.gov.di.ipv.core.library.exceptions.NoConfigForConnectionException;
import uk.gov.di.ipv.core.library.persistence.item.CriOAuthSessionItem;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;
import uk.org.webcompere.systemstubs.properties.SystemProperties;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.EC_PRIVATE_KEY_JWK;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.EC_PRIVATE_KEY_JWK_DOUBLE_ENCODED;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.RSA_ENCRYPTION_PUBLIC_JWK;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.RSA_ENCRYPTION_PUBLIC_JWK_DOUBLE_ENCODED;

@ExtendWith(MockitoExtension.class)
@ExtendWith(SystemStubsExtension.class)
class ConfigServiceTest {

    public static final CriOAuthSessionItem CRI_OAUTH_SESSION_ITEM =
            CriOAuthSessionItem.builder().criId("ukPassport").connection("main").build();
    private static final String TEST_CERT =
            "MIIC/TCCAeWgAwIBAgIBATANBgkqhkiG9w0BAQsFADAsMR0wGwYDVQQDDBRjcmktdWstcGFzc3BvcnQtYmFjazELMAkGA1UEBhMCR0IwHhcNMjExMjE3MTEwNTM5WhcNMjIxMjE3MTEwNTM5WjAsMR0wGwYDVQQDDBRjcmktdWstcGFzc3BvcnQtYmFjazELMAkGA1UEBhMCR0IwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDYIxWKwYNoz2MIDvYb2ip4nhCOGUccufIqwSHXl5FBOoOxOZh1rV57sWhdKO/hyZYbF5YUYTwzV4rW7DgLkfx0sN/p5igk74BZRSXvV/s+XCkVC5c0NDhNGh6WK5rc8Qbm0Ad5vEO1JpQih5y2mPGCwfLBqcY8AC7fwZinP/4YoMTCtEk5ueA0HwZLHXOEMWl/QCkj7WlSBL4i6ozk4So3RFL4awYP6nvhY7OLAcad7g/ZW0dXvztPOJnT9rwi1p6BNoD/Zk6jMJHhbvKyGsluUy7PYVGYCQ36Uuzby2Jq8cG5qNS+CBjy0/d/RmrClKd7gcnLY/J5NOC+YSynoHXRAgMBAAGjKjAoMA4GA1UdDwEB/wQEAwIFoDAWBgNVHSUBAf8EDDAKBggrBgEFBQcDBDANBgkqhkiG9w0BAQsFAAOCAQEAvHT2AGTymh02A9HWrnGm6PEXx2Ye3NXV9eJNU1z6J298mS2kYq0Z4D0hj9i8+IoCQRbWOxLTAWBNt/CmH7jWltE4uqoAwTZD6mDgkC2eo5dY+RcuydsvJNfTcvUOyi47KKGGEcddfLti4NuX51BQIY5vSBfqZXt8+y28WuWqBMh6eny2wJtxNHo20wQei5g7w19lqwJu2F+l/ykX9K5DHjhXqZUJ77YWmY8sy/WROLjOoZZRy6YuzV8S/+c/nsPzqDAkD4rpWwASjsEDaTcH22xpGq5XUAf1hwwNsuiyXKGUHCxafYYS781LR8pLg6DpEAgcn8tBbq6MoiEGVeOp7Q==";
    private static final String TEST_CERT_FS01 = "not a real cert";

    @SystemStub private EnvironmentVariables environmentVariables;

    @SystemStub private SystemProperties systemProperties;

    @Mock SSMProvider ssmProvider;

    @Mock SecretsProvider secretsProvider;

    private ConfigService configService;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        configService = new ConfigService(ssmProvider, secretsProvider);
    }

    @Nested
    @DisplayName("active credential issuer config")
    class ActiveOauthCriConfig {

        private final String oauthCriJsonConfig =
                String.format(
                        "{\"tokenUrl\":\"https://testTokenUrl\",\"credentialUrl\":\"https://testCredentialUrl\",\"authorizeUrl\":\"https://testAuthoriseUrl\",\"clientId\":\"ipv-core-test\",\"signingKey\":%s,\"encryptionKey\":%s,\"componentId\":\"https://testComponentId\",\"clientCallbackUrl\":\"https://testClientCallBackUrl\",\"requiresApiKey\":\"true\"}",
                        EC_PRIVATE_KEY_JWK_DOUBLE_ENCODED,
                        RSA_ENCRYPTION_PUBLIC_JWK_DOUBLE_ENCODED);

        private final OauthCriConfig expectedOauthCriConfig =
                OauthCriConfig.builder()
                        .tokenUrl(URI.create("https://testTokenUrl"))
                        .credentialUrl(URI.create("https://testCredentialUrl"))
                        .authorizeUrl(URI.create("https://testAuthoriseUrl"))
                        .clientId("ipv-core-test")
                        .signingKey(EC_PRIVATE_KEY_JWK)
                        .encryptionKey(RSA_ENCRYPTION_PUBLIC_JWK)
                        .componentId("https://testComponentId")
                        .clientCallbackUrl(URI.create("https://testClientCallBackUrl"))
                        .requiresApiKey(true)
                        .requiresAdditionalEvidence(false)
                        .build();

        @Test
        void getOauthCriActiveConnectionConfigShouldGetCredentialIssuerFromParameterStore() {
            environmentVariables.set("ENVIRONMENT", "test");

            when(ssmProvider.get("/test/core/credentialIssuers/passportCri/activeConnection"))
                    .thenReturn("stub");
            when(ssmProvider.get("/test/core/credentialIssuers/passportCri/connections/stub"))
                    .thenReturn(oauthCriJsonConfig);

            OauthCriConfig result = configService.getOauthCriActiveConnectionConfig("passportCri");

            assertEquals(expectedOauthCriConfig, result);
        }

        @Test
        void getOauthCriConfigShouldGetConfigForCriOauthSessionItem() {
            environmentVariables.set("ENVIRONMENT", "test");

            when(ssmProvider.get("/test/core/credentialIssuers/passportCri/connections/stub"))
                    .thenReturn(oauthCriJsonConfig);

            OauthCriConfig result =
                    configService.getOauthCriConfig(
                            CriOAuthSessionItem.builder()
                                    .criId("passportCri")
                                    .connection("stub")
                                    .build());

            assertEquals(expectedOauthCriConfig, result);
        }

        @Test
        void getOauthCriConfigForConnectionShouldGetOauthCriConfig() {
            environmentVariables.set("ENVIRONMENT", "test");
            when(ssmProvider.get("/test/core/credentialIssuers/passportCri/connections/stub"))
                    .thenReturn(oauthCriJsonConfig);

            var result = configService.getOauthCriConfigForConnection("stub", "passportCri");

            assertEquals(expectedOauthCriConfig, result);
        }

        @Test
        void getOauthCriConfigForConnectionShouldThrowIfNoCriConfigFound() {
            environmentVariables.set("ENVIRONMENT", "test");
            when(ssmProvider.get("/test/core/credentialIssuers/passportCri/connections/stub"))
                    .thenThrow(ParameterNotFoundException.builder().build());

            assertThrows(
                    NoConfigForConnectionException.class,
                    () -> configService.getOauthCriConfigForConnection("stub", "passportCri"));
        }

        @Test
        void getRestCriConfigShouldReturnARestCriConfig() throws Exception {
            environmentVariables.set("ENVIRONMENT", "test");

            when(ssmProvider.get("/test/core/credentialIssuers/restCri/activeConnection"))
                    .thenReturn("stub");
            when(ssmProvider.get("/test/core/credentialIssuers/restCri/connections/stub"))
                    .thenReturn(
                            String.format(
                                    "{\"credentialUrl\":\"https://testCredentialUrl\",\"signingKey\":%s,\"componentId\":\"https://testComponentId\",\"requiresApiKey\":\"true\"}",
                                    EC_PRIVATE_KEY_JWK_DOUBLE_ENCODED));

            RestCriConfig restCriConfig = configService.getRestCriConfig("restCri");

            var expectedRestCriConfig =
                    RestCriConfig.builder()
                            .credentialUrl(new URI("https://testCredentialUrl"))
                            .requiresApiKey(true)
                            .signingKey(EC_PRIVATE_KEY_JWK)
                            .componentId("https://testComponentId")
                            .build();

            assertEquals(expectedRestCriConfig, restCriConfig);
        }

        @Test
        void getCriConfigShouldReturnACriConfig() {
            environmentVariables.set("ENVIRONMENT", "test");

            when(ssmProvider.get("/test/core/credentialIssuers/cri/activeConnection"))
                    .thenReturn("stub");
            when(ssmProvider.get("/test/core/credentialIssuers/cri/connections/stub"))
                    .thenReturn(
                            String.format(
                                    "{\"signingKey\":%s,\"componentId\":\"https://testComponentId\"}",
                                    EC_PRIVATE_KEY_JWK_DOUBLE_ENCODED));

            CriConfig criConfig = configService.getCriConfig("cri");

            var expectedCriConfig =
                    CriConfig.builder()
                            .signingKey(EC_PRIVATE_KEY_JWK)
                            .componentId("https://testComponentId")
                            .build();

            assertEquals(expectedCriConfig, criConfig);
        }

        @Test
        void shouldGetComponentIdForActiveConnection() {
            environmentVariables.set("ENVIRONMENT", "test");

            final String testComponentId = "testComponentId";
            when(ssmProvider.get("/test/core/credentialIssuers/cri/activeConnection"))
                    .thenReturn("stub");
            when(ssmProvider.get("/test/core/credentialIssuers/cri/connections/stub"))
                    .thenReturn(
                            String.format(
                                    "{\"signingKey\":%s,\"componentId\":\"%s\"}",
                                    EC_PRIVATE_KEY_JWK_DOUBLE_ENCODED, testComponentId));

            assertEquals(testComponentId, configService.getComponentId("cri"));
        }
    }

    @ParameterizedTest
    @CsvSource({",", "' ',", "' \t\n',", "fs0001,fs0001"})
    void shouldNormaliseNullAndEmptyFeatureSetsToNull(
            String featureSet, String expectedFeatureSet) {
        configService.setFeatureSet(getFeatureSet(featureSet));
        assertEquals(
                (expectedFeatureSet != null && !expectedFeatureSet.isBlank())
                        ? Collections.singletonList(expectedFeatureSet)
                        : Collections.emptyList(),
                configService.getFeatureSet());
    }

    @Nested
    @DisplayName("credential issuer config items")
    class OauthCriConfigItems {

        private void setupTestData(
                String credentialIssuer,
                String attributeName,
                String baseValue,
                String featureSet,
                String featureSetValue) {
            environmentVariables.set("ENVIRONMENT", "test");
            configService.setFeatureSet(getFeatureSet(featureSet));
            if (featureSet == null) {
                when(ssmProvider.get(
                                String.format(
                                        "/test/core/credentialIssuers/%s/%s",
                                        credentialIssuer, attributeName)))
                        .thenReturn(baseValue);
            } else {
                when(ssmProvider.getMultiple(
                                String.format(
                                        "/test/core/features/%s/credentialIssuers/%s",
                                        featureSet, credentialIssuer)))
                        .thenReturn(Map.of(attributeName, featureSetValue));
            }
        }

        @ParameterizedTest
        @CsvSource({"main,stub,,main", "main,stub,fs01,stub"})
        void shouldGetActiveConnection(
                String baseActiveConnection,
                String featureSetActiveConnection,
                String featureSet,
                String expectedActiveConnection) {
            final String credentialIssuer = "passportCri";
            setupTestData(
                    credentialIssuer,
                    "activeConnection",
                    baseActiveConnection,
                    featureSet,
                    featureSetActiveConnection);
            assertEquals(
                    expectedActiveConnection, configService.getActiveConnection(credentialIssuer));
        }

        @ParameterizedTest
        @CsvSource({"true,false,,true", "true,false,fs01,false"})
        void shouldReturnIsEnabled(
                String baseIsEnabled,
                String featureSetIsEnabled,
                String featureSet,
                String expectedIsEnabled) {
            final String credentialIssuer = "passportCri";
            setupTestData(
                    credentialIssuer, "enabled", baseIsEnabled, featureSet, featureSetIsEnabled);
            assertEquals(
                    Boolean.parseBoolean(expectedIsEnabled),
                    configService.isEnabled(credentialIssuer));
        }

        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                value = {
                    "address,name|address,name,dob||address,name",
                    "address,name|address,name,dob|fs01|address,name,dob"
                })
        void shouldReturnAllowedSharedAttributes(
                String baseAllowedSharedAttributes,
                String featureSetAllowedSharedAttributes,
                String featureSet,
                String expectedIAllowedSharedAttributes) {
            final String credentialIssuer = "passportCri";
            setupTestData(
                    credentialIssuer,
                    "allowedSharedAttributes",
                    baseAllowedSharedAttributes,
                    featureSet,
                    featureSetAllowedSharedAttributes);
            assertEquals(
                    expectedIAllowedSharedAttributes,
                    configService.getAllowedSharedAttributes(credentialIssuer));
        }
    }

    @ParameterizedTest
    @CsvSource({
        "CLIENT_VALID_REDIRECT_URLS,",
        "CLIENT_VALID_REDIRECT_URLS,FS05",
        "CLIENT_VALID_REDIRECT_URLS,FS06_NO_OVERRIDE"
    })
    void shouldReturnListOfClientRedirectUrls(String testDataSet, String featureSet) {
        environmentVariables.set("ENVIRONMENT", "test");
        configService.setFeatureSet(getFeatureSet(featureSet));
        TestConfiguration testConfiguration = TestConfiguration.valueOf(testDataSet);
        testConfiguration.setupMockConfig(ssmProvider);
        assertEquals(
                Arrays.asList(testConfiguration.getExpectedValue(featureSet).split(",")),
                configService.getClientRedirectUrls("aClientId"));
    }

    @ParameterizedTest
    @CsvSource({"FEATURE_FLAGS,"})
    void shouldGetNamedFeatureFlag(String testDataSet, String featureSet) {
        TestConfiguration testConfiguration = getTestConfiguration(testDataSet, featureSet);
        assertEquals(
                Boolean.parseBoolean(testConfiguration.getExpectedValue(featureSet)),
                configService.enabled(TestFeatureFlag.TEST_FEATURE));
    }

    @Test
    void shouldGetNamedFeatureFlag_getParamValueForFirstFeatureSet() {
        String featureSet = "FS07,DS01";
        TestConfiguration testConfiguration = getTestConfiguration("FEATURE_FLAGS", featureSet);
        List<String> fsList = List.of(featureSet.split(","));
        assertEquals(
                Boolean.parseBoolean(testConfiguration.getExpectedValue(fsList.get(0))),
                configService.enabled(TestFeatureFlag.TEST_FEATURE));
    }

    @Test
    void shouldGetNamedFeatureFlag_loopThroughFeatureSetToFindParamValue() {
        String featureSet = "DS01,FS07";
        TestConfiguration testConfiguration = getTestConfiguration("FEATURE_FLAGS", featureSet);
        List<String> fsList = List.of(featureSet.split(","));
        assertEquals(
                Boolean.parseBoolean(testConfiguration.getExpectedValue(fsList.get(1))),
                configService.enabled(TestFeatureFlag.TEST_FEATURE));
    }

    private TestConfiguration getTestConfiguration(String testDataSet, String featureSet) {
        environmentVariables.set("ENVIRONMENT", "test");
        configService.setFeatureSet(
                featureSet != null ? List.of(featureSet.split(",")) : Collections.emptyList());
        TestConfiguration testConfiguration = TestConfiguration.valueOf(testDataSet);
        testConfiguration.setupMockConfig(ssmProvider);
        return testConfiguration;
    }

    @Test
    void getCriPrivateApiKeyForActiveConnectionShouldReturnApiKeySecret() {
        environmentVariables.set("ENVIRONMENT", "test");
        Map<String, String> apiKeySecret = Map.of("apiKey", "api-key-value");

        String json =
                assertDoesNotThrow(
                        () -> {
                            return OBJECT_MAPPER.writeValueAsString(apiKeySecret);
                        });

        when(secretsProvider.get("/test/credential-issuers/ukPassport/connections/stub/api-key"))
                .thenReturn(json);
        when(ssmProvider.get("/test/core/credentialIssuers/ukPassport/activeConnection"))
                .thenReturn("stub");

        String apiKey = configService.getCriPrivateApiKeyForActiveConnection("ukPassport");

        assertEquals("api-key-value", apiKey);
    }

    @Test
    void shouldGetSecretValueFromSecretsManager() {
        Map<String, String> apiKeySecret = Map.of("apiKey", "api-key-value");

        String json =
                assertDoesNotThrow(
                        () -> {
                            return OBJECT_MAPPER.writeValueAsString(apiKeySecret);
                        });

        when(secretsProvider.get(any())).thenReturn(json);

        String apiKey = configService.getCriPrivateApiKey(CRI_OAUTH_SESSION_ITEM);

        assertEquals("api-key-value", apiKey);
    }

    @Test
    void shouldReturnNullOnDecryptionFailureFromSecretsManager() {
        DecryptionFailureException decryptionFailureException =
                DecryptionFailureException.builder().message("Test decryption error").build();
        when(secretsProvider.get(any())).thenThrow(decryptionFailureException);

        String apiKey = configService.getCriPrivateApiKey(CRI_OAUTH_SESSION_ITEM);

        assertNull(apiKey);
    }

    @Test
    void shouldReturnNullOnInternalServiceErrorExceptionFromSecretsManager() {
        InternalServiceErrorException internalServiceErrorException =
                InternalServiceErrorException.builder()
                        .message("Test internal service error")
                        .build();
        when(secretsProvider.get(any())).thenThrow(internalServiceErrorException);

        String apiKey = configService.getCriPrivateApiKey(CRI_OAUTH_SESSION_ITEM);

        assertNull(apiKey);
    }

    @Test
    void shouldReturnNullOnInvalidParameterExceptionFromSecretsManager() {
        InvalidParameterException invalidParameterException =
                InvalidParameterException.builder().message("Test invalid parameter error").build();
        when(secretsProvider.get(any())).thenThrow(invalidParameterException);

        String apiKey = configService.getCriPrivateApiKey(CRI_OAUTH_SESSION_ITEM);

        assertNull(apiKey);
    }

    @Test
    void shouldReturnNullOnInvalidRequestExceptionFromSecretsManager() {
        InvalidRequestException invalidRequestException =
                InvalidRequestException.builder().message("Test invalid request error").build();
        when(secretsProvider.get(any())).thenThrow(invalidRequestException);

        String apiKey = configService.getCriPrivateApiKey(CRI_OAUTH_SESSION_ITEM);

        assertNull(apiKey);
    }

    @Test
    void shouldReturnNullOnResourceNotFoundExceptionFromSecretsManager() {
        ResourceNotFoundException resourceNotFoundException =
                ResourceNotFoundException.builder()
                        .message("Test resource not found error")
                        .build();
        when(secretsProvider.get(any())).thenThrow(resourceNotFoundException);

        String apiKey = configService.getCriPrivateApiKey(CRI_OAUTH_SESSION_ITEM);

        assertNull(apiKey);
    }

    @Test
    void shouldReturnNullOnInvalidApiKeyJsonFromSecretsManager() {
        when(secretsProvider.get(any())).thenReturn("{\"apiKey\":\"invalidJson}");
        String apiKey = configService.getCriPrivateApiKey(CRI_OAUTH_SESSION_ITEM);
        assertNull(apiKey);
    }

    @Test
    void shouldGetContraIndicatorConfigMap() {
        String scoresJsonString =
                "[{\"ci\":\"X01\",\"detectedScore\":3,\"checkedScore\":-3,\"returnCode\":\"1\"},{\"ci\":\"Z03\",\"detectedScore\":5,\"checkedScore\":-3,\"returnCode\":\"1\"}]";
        when(secretsProvider.get(any())).thenReturn(scoresJsonString);

        Map<String, ContraIndicatorConfig> configMap = configService.getContraIndicatorConfigMap();

        assertEquals(2, configMap.size());
        assertTrue(configMap.containsKey("X01"));
        assertTrue(configMap.containsKey("Z03"));
        assertEquals("X01", configMap.get("X01").getCi());
        assertEquals(3, configMap.get("X01").getDetectedScore());
        assertEquals(-3, configMap.get("X01").getCheckedScore());
        assertEquals("1", configMap.get("X01").getReturnCode());
    }

    @Test
    void shouldReturnEmptyCollectionOnInvalidContraIndicatorConfigsMap() {
        final String invalidCIConfigJsonString =
                "[\"ci\":\"X01\",\"detectedScore\":3,\"checkedScore\":-3,\"returnCode\":\"1\"}]";
        when(secretsProvider.get(any())).thenReturn(invalidCIConfigJsonString);
        Map<String, ContraIndicatorConfig> configMap = configService.getContraIndicatorConfigMap();
        assertTrue(configMap.isEmpty());
    }

    @Test
    void shouldGetBearerAccessTokenTtlFromEnvironmentVariableIfSet() {
        environmentVariables.set("BEARER_TOKEN_TTL", "1800");
        assertEquals(1800L, configService.getBearerAccessTokenTtl());
    }

    @Test
    void shouldDefaultBearerAccessTokenTtlIfEnvironmentVariableNotSet() {
        assertEquals(3600L, configService.getBearerAccessTokenTtl());
    }

    @Test
    void shouldGetSigningKeyIdParamNamedByEnvironmentVariable() {
        final String signingKeyIdPath = "/test/core/self/signingKeyId";
        final String testSigningKeyId = "6CA2A18E-AFAD-41B4-95EC-53F967A290BE";
        environmentVariables.set("SIGNING_KEY_ID_PARAM", signingKeyIdPath);
        when(ssmProvider.get(signingKeyIdPath)).thenReturn(testSigningKeyId);
        assertEquals(testSigningKeyId, configService.getSigningKeyId());
    }

    @ParameterizedTest
    @CsvSource({
        "PUBLIC_KEY_MATERIAL_FOR_CORE_TO_VERIFY,",
        "PUBLIC_KEY_MATERIAL_FOR_CORE_TO_VERIFY,FS01",
        "CLIENT_ISSUER,",
        "CLIENT_ISSUER,FS02",
        "CLIENT_ISSUER,FS03_NO_OVERRIDE"
    })
    void shouldAccountForFeatureSetWhenRetrievingParameterForClient(
            String configVariableName, String featureSet) {
        configService = new ConfigService(ssmProvider, secretsProvider, getFeatureSet(featureSet));
        environmentVariables.set("ENVIRONMENT", "test");
        ConfigurationVariable configurationVariable =
                ConfigurationVariable.valueOf(configVariableName);
        TestConfiguration testConfiguration = TestConfiguration.valueOf(configVariableName);
        testConfiguration.setupMockConfig(ssmProvider);
        assertEquals(
                testConfiguration.getExpectedValue(featureSet),
                configService.getSsmParameter(configurationVariable, "aClientId"));
    }

    @ParameterizedTest
    @CsvSource({
        "MAX_ALLOWED_AUTH_CLIENT_TTL,",
        "MAX_ALLOWED_AUTH_CLIENT_TTL,FS01",
        "CORE_VTM_CLAIM,",
        "CORE_VTM_CLAIM,FS02",
        "BACKEND_SESSION_TIMEOUT,",
        "BACKEND_SESSION_TIMEOUT,FS03",
        "BACKEND_SESSION_TTL,",
        "BACKEND_SESSION_TTL,FS04",
        "BACKEND_SESSION_TTL,FS05_NO_OVERRIDE",
    })
    void shouldAccountForFeatureSetWhenRetrievingParameter(
            String configVariableName, String featureSet) {
        configService = new ConfigService(ssmProvider, secretsProvider, getFeatureSet(featureSet));
        environmentVariables.set("ENVIRONMENT", "test");
        ConfigurationVariable configurationVariable =
                ConfigurationVariable.valueOf(configVariableName);
        TestConfiguration testConfiguration = TestConfiguration.valueOf(configVariableName);
        testConfiguration.setupMockConfig(ssmProvider);
        assertEquals(
                testConfiguration.getExpectedValue(featureSet),
                configService.getSsmParameter(configurationVariable));
    }

    private enum TestConfiguration {
        PUBLIC_KEY_MATERIAL_FOR_CORE_TO_VERIFY(
                "clients/aClientId/publicKeyMaterialForCoreToVerify",
                TEST_CERT,
                Map.of("FS01", TEST_CERT_FS01)),
        CLIENT_ISSUER(
                "clients/aClientId/issuer", "aClientIssuer", Map.of("FS02", "aDifferentIssuer")),
        MAX_ALLOWED_AUTH_CLIENT_TTL(
                "self/maxAllowedAuthClientTtl",
                "aClientTokenTtl",
                Map.of("FS01", "aDifferentClientTokenTtl")),
        CORE_VTM_CLAIM(
                "self/coreVtmClaim", "aCoreVtmClaim", Map.of("FS02", "aDifferentCoreVtmClaim")),
        BACKEND_SESSION_TIMEOUT(
                "self/backendSessionTimeout", "7200", Map.of("FS02", "7300", "FS03", "7400")),
        BACKEND_SESSION_TTL(
                "self/backendSessionTtl", "3600", Map.of("FS03", "3700", "FS04", "3800")),
        CLIENT_VALID_REDIRECT_URLS(
                "clients/aClientId/validRedirectUrls",
                "one.example.com/callback,two.example.com/callback,three.example.com/callback",
                Map.of("FS05", "one.example.com/callback,four.example.com/callback")),
        FEATURE_FLAGS("featureFlags/testFeature", "false", Map.of("FS07", "true"));

        private final String path;
        private final String baseValue;
        private final Map<String, String> featureSetValues;

        TestConfiguration(String path, String baseValue, Map<String, String> featureSetValues) {
            this.path = path;
            this.baseValue = baseValue;
            this.featureSetValues = featureSetValues;
        }

        public void setupMockConfig(SSMProvider ssmProvider) {
            Mockito.lenient().when(ssmProvider.get("/test/core/" + path)).thenReturn(baseValue);
            final Path parameterPath = Path.of(path);
            final String terminal = parameterPath.getFileName().toString();
            final String basePath = parameterPath.getParent().toString();
            featureSetValues.forEach(
                    (featureSet, valueOverride) ->
                            Mockito.lenient()
                                    .when(
                                            ssmProvider.getMultiple(
                                                    "/test/core/features/"
                                                            + featureSet
                                                            + "/"
                                                            + basePath))
                                    .thenReturn(Map.of(terminal, valueOverride)));
        }

        public String getExpectedValue(String featureSet) {
            if (featureSet == null) {
                return baseValue;
            } else {
                return featureSetValues.getOrDefault(featureSet, baseValue);
            }
        }
    }

    private enum TestFeatureFlag implements FeatureFlag {
        TEST_FEATURE("testFeature");
        private final String name;

        TestFeatureFlag(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return this.name;
        }
    }

    private enum TestMultipleConfiguration {
        CREDENTIAL_ISSUERS(
                "credentialIssuers",
                Map.of(
                        "cri1/activeConnection",
                        "stub",
                        "cri1/connections/stub/clientId",
                        "ipv-core",
                        "cri2/activeConnection",
                        "main",
                        "cri2/connections/main/clientId",
                        "a client id"),
                Map.of(
                        "FS01",
                        Map.of("cri2/activeConnection", "stub", "cri3/activeConnection", "main")));

        private final String path;
        private final Map<String, String> baseValues;
        private final Map<String, Map<String, String>> featureSetValues;

        TestMultipleConfiguration(
                String path,
                Map<String, String> baseValues,
                Map<String, Map<String, String>> featureSetValues) {
            this.path = path;
            this.baseValues = baseValues;
            this.featureSetValues = featureSetValues;
        }

        public void setupMockConfig(SSMProvider ssmProvider) {
            Mockito.lenient()
                    .when(ssmProvider.getMultiple("/test/core/" + path))
                    .thenReturn(baseValues);
            featureSetValues.forEach(
                    (featureSet, valueOverride) ->
                            Mockito.lenient()
                                    .when(
                                            ssmProvider.getMultiple(
                                                    "/test/core/features/"
                                                            + featureSet
                                                            + "/"
                                                            + path))
                                    .thenReturn(valueOverride));
        }

        public Map<String, String> getExpectedValue(String featureSet) {
            if (featureSet == null) {
                return baseValues;
            } else {
                var expected = new HashMap<>(baseValues);
                expected.putAll(featureSetValues.get(featureSet));
                return expected;
            }
        }
    }

    @ParameterizedTest
    @MethodSource("provideConfiguredSsmCimitConfig")
    void shouldFetchCimitConfig(String cimitSsmConfig, String expectedDocument)
            throws ConfigException {
        environmentVariables.set("ENVIRONMENT", "test");
        when(ssmProvider.get("/test/core/cimit/config")).thenReturn(cimitSsmConfig);
        Map<String, List<MitigationRoute>> expectedCiMitConfig =
                Map.of(
                        "X01",
                        List.of(new MitigationRoute("/journey/do-a-thing", expectedDocument)));
        Map<String, List<MitigationRoute>> cimitConfig = configService.getCimitConfig();
        assertEquals(
                expectedCiMitConfig.get("X01").get(0).event(),
                cimitConfig.get("X01").get(0).event());
        assertEquals(
                expectedCiMitConfig.get("X01").get(0).document(),
                cimitConfig.get("X01").get(0).document());
    }

    private static Stream<Arguments> provideConfiguredSsmCimitConfig() {
        return Stream.of(
                Arguments.of("{\"X01\": [{\"event\": \"/journey/do-a-thing\"}]}", null),
                Arguments.of(
                        "{\"X01\": [{\"event\": \"/journey/do-a-thing\", \"document\": \"drivingPermit\"}]}",
                        "drivingPermit"));
    }

    @Test
    void shouldThrowErrorOnInvalidCimitConfig() {
        environmentVariables.set("ENVIRONMENT", "test");
        when(ssmProvider.get("/test/core/cimit/config")).thenReturn("}");
        assertThrows(ConfigException.class, () -> configService.getCimitConfig());
    }

    @Test
    void enabledShowReturnTrueIfFeatureFlagEnabledGivenName() {
        environmentVariables.set("ENVIRONMENT", "test");
        when(ssmProvider.get("/test/core/featureFlags/testFlagName")).thenReturn("true");
        assertTrue(configService.enabled("testFlagName"));
    }

    @Test
    void enabledShowReturnFalseIfFeatureFlagNotEnabledGivenName() {
        environmentVariables.set("ENVIRONMENT", "test");
        when(ssmProvider.get("/test/core/featureFlags/testFlagName")).thenReturn("false");
        assertFalse(configService.enabled("testFlagName"));
    }

    private static List<String> getFeatureSet(String featureSet) {
        return (featureSet != null && !featureSet.isBlank())
                ? Collections.singletonList(featureSet)
                : Collections.emptyList();
    }
}
