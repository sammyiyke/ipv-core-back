package uk.gov.di.ipv.core.library.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.di.ipv.core.library.config.ConfigurationVariable;
import uk.gov.di.ipv.core.library.domain.BirthDate;
import uk.gov.di.ipv.core.library.domain.ContraIndicatorConfig;
import uk.gov.di.ipv.core.library.domain.ContraIndicators;
import uk.gov.di.ipv.core.library.domain.ErrorResponse;
import uk.gov.di.ipv.core.library.domain.IdentityClaim;
import uk.gov.di.ipv.core.library.domain.Name;
import uk.gov.di.ipv.core.library.domain.NameParts;
import uk.gov.di.ipv.core.library.domain.ReturnCode;
import uk.gov.di.ipv.core.library.domain.UserIdentity;
import uk.gov.di.ipv.core.library.domain.cimitvc.ContraIndicator;
import uk.gov.di.ipv.core.library.domain.cimitvc.Mitigation;
import uk.gov.di.ipv.core.library.dto.OauthCriConfig;
import uk.gov.di.ipv.core.library.dto.VcStatusDto;
import uk.gov.di.ipv.core.library.enums.Vot;
import uk.gov.di.ipv.core.library.exceptions.CredentialParseException;
import uk.gov.di.ipv.core.library.exceptions.HttpResponseExceptionWithErrorBody;
import uk.gov.di.ipv.core.library.exceptions.NoVcStatusForIssuerException;
import uk.gov.di.ipv.core.library.exceptions.UnrecognisedCiException;
import uk.gov.di.ipv.core.library.persistence.DataStore;
import uk.gov.di.ipv.core.library.persistence.item.VcStoreItem;

import java.net.URI;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.CI_SCORING_THRESHOLD;
import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.CORE_VTM_CLAIM;
import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.RETURN_CODES_ALWAYS_REQUIRED;
import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.RETURN_CODES_NON_CI_BREACHING_P0;
import static uk.gov.di.ipv.core.library.domain.CriConstants.ADDRESS_CRI;
import static uk.gov.di.ipv.core.library.domain.CriConstants.BAV_CRI;
import static uk.gov.di.ipv.core.library.domain.CriConstants.DCMAW_CRI;
import static uk.gov.di.ipv.core.library.domain.CriConstants.F2F_CRI;
import static uk.gov.di.ipv.core.library.domain.CriConstants.FRAUD_CRI;
import static uk.gov.di.ipv.core.library.domain.CriConstants.KBV_CRI;
import static uk.gov.di.ipv.core.library.domain.CriConstants.NINO_CRI;
import static uk.gov.di.ipv.core.library.domain.CriConstants.NON_EVIDENCE_CRI_TYPES;
import static uk.gov.di.ipv.core.library.domain.CriConstants.PASSPORT_CRI;
import static uk.gov.di.ipv.core.library.domain.CriConstants.TICF_CRI;
import static uk.gov.di.ipv.core.library.domain.VerifiableCredentialConstants.VC_BIRTH_DATE;
import static uk.gov.di.ipv.core.library.domain.VerifiableCredentialConstants.VC_CLAIM;
import static uk.gov.di.ipv.core.library.domain.VerifiableCredentialConstants.VC_CREDENTIAL_SUBJECT;
import static uk.gov.di.ipv.core.library.domain.VerifiableCredentialConstants.VC_FAMILY_NAME;
import static uk.gov.di.ipv.core.library.domain.VerifiableCredentialConstants.VC_GIVEN_NAME;
import static uk.gov.di.ipv.core.library.domain.VerifiableCredentialConstants.VC_NAME;
import static uk.gov.di.ipv.core.library.domain.VocabConstants.ADDRESS_CLAIM_NAME;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.EC_PRIVATE_KEY_JWK;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.M1A_F2F_VC;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.M1B_DCMAW_VC;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.VC_ADDRESS;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.VC_ADDRESS_2;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.VC_ADDRESS_MISSING_ADDRESS_PROPERTY;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.VC_DRIVING_PERMIT_DCMAW;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.VC_DRIVING_PERMIT_DCMAW_FAILED;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.VC_DRIVING_PERMIT_DCMAW_MISSING_DRIVING_PERMIT_PROPERTY;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.VC_FRAUD_SCORE_1;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.VC_FRAUD_WITHOUT_NAME;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.VC_KBV_SCORE_2;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.VC_NINO_MISSING_SOCIAL_SECURITY_RECORD;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.VC_NINO_SUCCESSFUL;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.VC_NINO_UNSUCCESSFUL;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.VC_PASSPORT_MISSING_BIRTH_DATE;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.VC_PASSPORT_MISSING_NAME;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.VC_PASSPORT_MISSING_PASSPORT;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.VC_PASSPORT_NON_DCMAW_SUCCESSFUL;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.VC_TICF;

@ExtendWith(MockitoExtension.class)
class UserIdentityServiceTest {
    public static final JWSHeader JWS_HEADER =
            new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).build();
    private static final String USER_ID_1 = "user-id-1";
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static ECDSASigner jwtSigner;
    @Mock private ConfigService mockConfigService;
    @Mock private DataStore<VcStoreItem> mockDataStore;
    private final ContraIndicators emptyContraIndicators =
            ContraIndicators.builder().contraIndicatorsMap(new HashMap<>()).build();
    private UserIdentityService userIdentityService;
    private final Map<ConfigurationVariable, String> paramsToMockForP2 =
            Map.of(CORE_VTM_CLAIM, "mock-vtm-claim");
    private final Map<ConfigurationVariable, String> paramsToMockForP0 =
            Map.of(CORE_VTM_CLAIM, "mock-vtm-claim", CI_SCORING_THRESHOLD, "0");
    private final Map<ConfigurationVariable, String> paramsToMockForP0WithNoCi =
            Map.of(
                    CORE_VTM_CLAIM,
                    "mock-vtm-claim",
                    CI_SCORING_THRESHOLD,
                    "0",
                    RETURN_CODES_NON_CI_BREACHING_P0,
                    "🐧");
    public static OauthCriConfig claimedIdentityConfig;

    @BeforeAll
    static void beforeAllSetUp() throws Exception {
        jwtSigner = new ECDSASigner(ECKey.parse(EC_PRIVATE_KEY_JWK).toECPrivateKey());
        claimedIdentityConfig =
                OauthCriConfig.builder()
                        .tokenUrl(new URI("http://example.com/token"))
                        .credentialUrl(new URI("http://example.com/credential"))
                        .authorizeUrl(new URI("http://example.com/authorize"))
                        .clientId("ipv-core")
                        .signingKey("test-jwk")
                        .encryptionKey("test-encryption-jwk")
                        .componentId("https://review-a.integration.account.gov.uk")
                        .clientCallbackUrl(new URI("http://example.com/redirect"))
                        .requiresApiKey(true)
                        .requiresAdditionalEvidence(false)
                        .build();
    }

    @BeforeEach
    void setUp() {
        userIdentityService = new UserIdentityService(mockConfigService, mockDataStore);
    }

    @Test
    void shouldReturnCredentialsFromDataStore()
            throws HttpResponseExceptionWithErrorBody, CredentialParseException {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                VC_PASSPORT_NON_DCMAW_SUCCESSFUL,
                                Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()));

        mockParamStoreCalls(paramsToMockForP2);
        mockCredentialIssuerConfig();
        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P2.name(), emptyContraIndicators);

        // Assert
        assertEquals(VC_PASSPORT_NON_DCMAW_SUCCESSFUL, credentials.getVcs().get(0));
        assertEquals(VC_FRAUD_SCORE_1, credentials.getVcs().get(1));
        assertEquals("test-sub", credentials.getSub());
    }

    @Test
    void shouldSetVotClaimToP2OnSuccessfulIdentityCheck() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                VC_PASSPORT_NON_DCMAW_SUCCESSFUL,
                                Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, KBV_CRI, VC_KBV_SCORE_2, Instant.now()),
                        createVcStoreItem(USER_ID_1, ADDRESS_CRI, VC_ADDRESS, Instant.now()));

        mockParamStoreCalls(paramsToMockForP2);
        mockCredentialIssuerConfig();
        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P2.name(), emptyContraIndicators);

        // Assert
        assertEquals(Vot.P2.name(), credentials.getVot());
    }

    @Test
    void areVCsCorrelatedReturnsTrueWhenVcAreCorrelated() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                ADDRESS_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                BAV_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(USER_ID_1, TICF_CRI, VC_TICF, Instant.now()));
        mockCredentialIssuerConfig();

        // Act & Assert
        assertTrue(userIdentityService.areVCsCorrelated(vcStoreItems));
    }

    @Test
    void areVCsCorrelatedReturnFalseWhenNamesDiffer() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                ADDRESS_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Corky", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                FRAUD_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(USER_ID_1, TICF_CRI, VC_TICF, Instant.now()));
        mockCredentialIssuerConfig();

        // Act & Assert
        assertFalse(userIdentityService.areVCsCorrelated(vcStoreItems));
    }

    @Test
    void areVCsCorrelatedReturnFalseWhenNameDifferentForBavCRI() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                DCMAW_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                BAV_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimmy", "Jones", ""), // BAV cri doesn't provide birthdate
                                Instant.now()));
        mockCredentialIssuerConfig();

        // Act & Assert
        assertFalse(userIdentityService.areVCsCorrelated(vcStoreItems));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void areVCsCorrelatedShouldThrowExceptionWhenVcHasMissingGivenName(String missingName)
            throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                FRAUD_CRI,
                                createCredentialWithNameAndBirthDate(
                                        missingName, "Jones", "1000-01-01"),
                                Instant.now()));
        mockCredentialIssuerConfig();

        // Act & Assert
        HttpResponseExceptionWithErrorBody thrownError =
                assertThrows(
                        HttpResponseExceptionWithErrorBody.class,
                        () -> userIdentityService.areVCsCorrelated(vcStoreItems));

        assertEquals(500, thrownError.getResponseCode());
        assertEquals(
                ErrorResponse.FAILED_NAME_CORRELATION.getCode(),
                thrownError.getErrorBody().get("error"));
        assertEquals(
                ErrorResponse.FAILED_NAME_CORRELATION.getMessage(),
                thrownError.getErrorBody().get("error_description"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void areVCsCorrelatedShouldThrowExceptionWhenVcHasMissingFamilyName(String missingName)
            throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", missingName, "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                FRAUD_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()));
        mockCredentialIssuerConfig();

        // Act & Assert
        HttpResponseExceptionWithErrorBody thrownError =
                assertThrows(
                        HttpResponseExceptionWithErrorBody.class,
                        () -> userIdentityService.areVCsCorrelated(vcStoreItems));

        assertEquals(500, thrownError.getResponseCode());
        assertEquals(
                ErrorResponse.FAILED_NAME_CORRELATION.getCode(),
                thrownError.getErrorBody().get("error"));
        assertEquals(
                ErrorResponse.FAILED_NAME_CORRELATION.getMessage(),
                thrownError.getErrorBody().get("error_description"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void areVCsCorrelatedShouldReturnTrueWhenAddressVcHasMissingName(String missing)
            throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                FRAUD_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                ADDRESS_CRI,
                                createCredentialWithNameAndBirthDate(
                                        missing, missing, "1000-01-01"),
                                Instant.now()));
        mockCredentialIssuerConfig();

        // Act & Assert
        assertTrue(userIdentityService.areVCsCorrelated(vcStoreItems));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void areVCsCorrelatedShouldReturnFalseWhenMissingNameCredentialForBAVCRI(String missing)
            throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                DCMAW_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                BAV_CRI,
                                createCredentialWithNameAndBirthDate(missing, "Jones", missing),
                                Instant.now()));
        mockCredentialIssuerConfig();

        // Act & Assert
        HttpResponseExceptionWithErrorBody thrownError =
                assertThrows(
                        HttpResponseExceptionWithErrorBody.class,
                        () -> userIdentityService.areVCsCorrelated(vcStoreItems));

        assertEquals(500, thrownError.getResponseCode());
        assertEquals(
                ErrorResponse.FAILED_NAME_CORRELATION.getCode(),
                thrownError.getErrorBody().get("error"));
        assertEquals(
                ErrorResponse.FAILED_NAME_CORRELATION.getMessage(),
                thrownError.getErrorBody().get("error_description"));
    }

    @Test
    void areVCsCorrelatedShouldReturnFalseIfExtraGivenNameInVc() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                ADDRESS_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jimmy", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                BAV_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()));
        mockCredentialIssuerConfig();

        // Act & Assert
        assertFalse(userIdentityService.areVCsCorrelated(vcStoreItems));
    }

    @Test
    void areVCsCorrelatedReturnsFalseWhenBirthDatesDiffer() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                DCMAW_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                FRAUD_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "2000-01-01"),
                                Instant.now()));
        mockCredentialIssuerConfig();

        // Act & Assert
        assertFalse(userIdentityService.areVCsCorrelated(vcStoreItems));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void areVCsCorrelatedShouldThrowExceptionWhenMissingBirthDateProperty(String missing)
            throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                ADDRESS_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                createCredentialWithNameAndBirthDate("Jimbo", "Jones", missing),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                FRAUD_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()));
        mockCredentialIssuerConfig();

        // Act & Assert
        HttpResponseExceptionWithErrorBody thrownError =
                assertThrows(
                        HttpResponseExceptionWithErrorBody.class,
                        () -> userIdentityService.areVCsCorrelated(vcStoreItems));

        assertEquals(500, thrownError.getResponseCode());
        assertEquals(
                ErrorResponse.FAILED_BIRTHDATE_CORRELATION.getCode(),
                thrownError.getErrorBody().get("error"));
        assertEquals(
                ErrorResponse.FAILED_BIRTHDATE_CORRELATION.getMessage(),
                thrownError.getErrorBody().get("error_description"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void areVCsCorrelatedShouldReturnTrueWhenAddressHasMissingBirthDate(String missing)
            throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                ADDRESS_CRI,
                                createCredentialWithNameAndBirthDate("Jimbo", "Jones", missing),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                FRAUD_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()));
        mockCredentialIssuerConfig();

        // Act & Assert
        assertTrue(userIdentityService.areVCsCorrelated(vcStoreItems));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void areVCsCorrelatedShouldReturnTrueWhenBavHasMissingBirthDate(String missing)
            throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                BAV_CRI,
                                createCredentialWithNameAndBirthDate("Jimbo", "Jones", missing),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                FRAUD_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()));
        mockCredentialIssuerConfig();

        // Act & Assert
        assertTrue(userIdentityService.areVCsCorrelated(vcStoreItems));
    }

    @Test
    void areVCsCorrelatedShouldReturnFalseIfBavHasDifferentBirthDate() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                ADDRESS_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                BAV_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "2000-01-01"),
                                Instant.now()));
        mockCredentialIssuerConfig();

        // Act & Assert
        assertFalse(userIdentityService.areVCsCorrelated(vcStoreItems));
    }

    @Test
    void areVCsCorrelatedShouldNotIncludeVCsForNameNotDeemedSuccessful() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                ADDRESS_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Corky", "Jones", "1000-01-01", false),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                FRAUD_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()));
        mockCredentialIssuerConfig();

        // Act & Assert
        assertTrue(userIdentityService.areVCsCorrelated(vcStoreItems));
    }

    @Test
    void areVCsCorrelatedShouldNotIncludeVCsForDOBNotDeemedSuccessful() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                ADDRESS_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                FRAUD_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "2000-01-01", false),
                                Instant.now()));
        mockCredentialIssuerConfig();

        // Act & Assert
        assertTrue(userIdentityService.areVCsCorrelated(vcStoreItems));
    }

    @Test
    void areVCsCorrelatedReturnsFalseWhenExtraBirthDateInVc() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                DCMAW_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", "1000-01-01"),
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                FRAUD_CRI,
                                createCredentialWithNameAndBirthDate(
                                        "Jimbo", "Jones", List.of("1000-01-01", "2000-01-01")),
                                Instant.now()));
        mockCredentialIssuerConfig();

        // Act & Assert
        assertFalse(userIdentityService.areVCsCorrelated(vcStoreItems));
    }

    @Test
    void shouldSetIdentityClaimWhenVotIsP2() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                VC_PASSPORT_NON_DCMAW_SUCCESSFUL,
                                Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, KBV_CRI, VC_KBV_SCORE_2, Instant.now()),
                        createVcStoreItem(USER_ID_1, ADDRESS_CRI, VC_ADDRESS, Instant.now()));

        mockParamStoreCalls(paramsToMockForP2);
        mockCredentialIssuerConfig();
        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P2.name(), emptyContraIndicators);

        // Assert
        IdentityClaim identityClaim = credentials.getIdentityClaim();

        assertEquals("GivenName", identityClaim.getName().get(0).getNameParts().get(0).getType());
        assertEquals("Paul", identityClaim.getName().get(0).getNameParts().get(0).getValue());

        assertEquals("2020-02-03", identityClaim.getBirthDate().get(0).getValue());
    }

    @Test
    void shouldSetIdentityClaimWhenVotIsP2MissingName() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1, PASSPORT_CRI, VC_PASSPORT_MISSING_NAME, Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                DCMAW_CRI,
                                VC_PASSPORT_MISSING_BIRTH_DATE,
                                Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, KBV_CRI, VC_KBV_SCORE_2, Instant.now()),
                        createVcStoreItem(USER_ID_1, ADDRESS_CRI, VC_ADDRESS, Instant.now()));

        mockParamStoreCalls(paramsToMockForP2);
        mockCredentialIssuerConfig();
        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P2.name(), emptyContraIndicators);

        // Assert
        IdentityClaim identityClaim = credentials.getIdentityClaim();

        assertEquals("GivenName", identityClaim.getName().get(0).getNameParts().get(0).getType());
        assertEquals("Paul", identityClaim.getName().get(0).getNameParts().get(0).getValue());

        assertEquals("2020-02-03", identityClaim.getBirthDate().get(0).getValue());
    }

    @Test
    void shouldNotSetIdentityClaimWhenVotIsP0() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                VC_PASSPORT_NON_DCMAW_SUCCESSFUL,
                                Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()));

        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);
        mockParamStoreCalls(paramsToMockForP0WithNoCi);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P0.name(), emptyContraIndicators);

        // Assert
        assertNull(credentials.getIdentityClaim());
    }

    @Test
    void shouldGetCorrectVot() throws ParseException {
        // Arrange
        JWTClaimsSet claims = new JWTClaimsSet.Builder().claim("vot", Vot.PCL200.name()).build();
        SignedJWT signedJWT = new SignedJWT(JWS_HEADER, claims);

        // Act
        var vot = userIdentityService.getVot(signedJWT);

        // Assert
        assertEquals(Vot.PCL200, vot);
    }

    @Test
    void shouldThrowForInvalidVot() {
        // Arrange
        JWTClaimsSet claims = new JWTClaimsSet.Builder().claim("vot", "not a vot value").build();
        SignedJWT signedJWT = new SignedJWT(JWS_HEADER, claims);

        // Act
        IllegalArgumentException thrownException =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> userIdentityService.getVot(signedJWT));

        // Assert
        assertEquals(
                "No enum constant uk.gov.di.ipv.core.library.enums.Vot.not a vot value",
                thrownException.getMessage());
    }

    @Test
    void shouldThrowExceptionWhenMissingNameProperty() {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1, PASSPORT_CRI, VC_PASSPORT_MISSING_NAME, Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, KBV_CRI, VC_KBV_SCORE_2, Instant.now()));

        when(mockConfigService.getSsmParameter(CORE_VTM_CLAIM)).thenReturn("mock-vtm-claim");
        mockCredentialIssuerConfig();
        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);

        // Act & Assert
        HttpResponseExceptionWithErrorBody thrownError =
                assertThrows(
                        HttpResponseExceptionWithErrorBody.class,
                        () ->
                                userIdentityService.generateUserIdentity(
                                        USER_ID_1,
                                        "test-sub",
                                        Vot.P2.name(),
                                        emptyContraIndicators));

        assertEquals(500, thrownError.getResponseCode());
        assertEquals(
                ErrorResponse.FAILED_TO_GENERATE_IDENTIY_CLAIM.getCode(),
                thrownError.getErrorBody().get("error"));
        assertEquals(
                ErrorResponse.FAILED_TO_GENERATE_IDENTIY_CLAIM.getMessage(),
                thrownError.getErrorBody().get("error_description"));
    }

    @Test
    void shouldThrowExceptionWhenMissingBirthDateProperty() {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                VC_PASSPORT_MISSING_BIRTH_DATE,
                                Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, KBV_CRI, VC_KBV_SCORE_2, Instant.now()));

        when(mockConfigService.getSsmParameter(CORE_VTM_CLAIM)).thenReturn("mock-vtm-claim");
        mockCredentialIssuerConfig();
        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);

        // Act & Assert
        HttpResponseExceptionWithErrorBody thrownError =
                assertThrows(
                        HttpResponseExceptionWithErrorBody.class,
                        () ->
                                userIdentityService.generateUserIdentity(
                                        USER_ID_1,
                                        "test-sub",
                                        Vot.P2.name(),
                                        emptyContraIndicators));

        assertEquals(500, thrownError.getResponseCode());
        assertEquals(
                ErrorResponse.FAILED_TO_GENERATE_IDENTIY_CLAIM.getCode(),
                thrownError.getErrorBody().get("error"));
        assertEquals(
                ErrorResponse.FAILED_TO_GENERATE_IDENTIY_CLAIM.getMessage(),
                thrownError.getErrorBody().get("error_description"));
    }

    @Test
    void shouldSetPassportClaimWhenVotIsP2() throws Exception {
        // Arrange
        mockParamStoreCalls(paramsToMockForP2);
        mockCredentialIssuerConfig();

        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                VC_PASSPORT_NON_DCMAW_SUCCESSFUL,
                                Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, KBV_CRI, VC_KBV_SCORE_2, Instant.now()),
                        createVcStoreItem(USER_ID_1, ADDRESS_CRI, VC_ADDRESS, Instant.now()));

        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P2.name(), emptyContraIndicators);

        // Assert
        JsonNode passportClaim = credentials.getPassportClaim();

        assertEquals("123456789", passportClaim.get(0).get("documentNumber").asText());
        assertEquals("2020-01-01", passportClaim.get(0).get("expiryDate").asText());
    }

    @Test
    void shouldNotSetPassportClaimWhenVotIsP0() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                VC_PASSPORT_NON_DCMAW_SUCCESSFUL,
                                Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()));

        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);
        mockParamStoreCalls(paramsToMockForP0WithNoCi);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P0.name(), emptyContraIndicators);

        // Assert
        assertNull(credentials.getPassportClaim());
    }

    @Test
    void shouldReturnEmptyWhenMissingPassportProperty() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                VC_PASSPORT_MISSING_PASSPORT,
                                Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, KBV_CRI, VC_KBV_SCORE_2, Instant.now()),
                        createVcStoreItem(USER_ID_1, ADDRESS_CRI, VC_ADDRESS, Instant.now()));

        mockParamStoreCalls(paramsToMockForP2);
        mockCredentialIssuerConfig();
        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P2.name(), emptyContraIndicators);

        // Assert
        assertNull(credentials.getPassportClaim());
    }

    @Test
    void generateUserIdentityShouldSetNinoClaimWhenVotIsP2() throws Exception {
        // Arrange
        mockParamStoreCalls(paramsToMockForP2);
        mockCredentialIssuerConfig();

        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1, DCMAW_CRI, VC_DRIVING_PERMIT_DCMAW, Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, KBV_CRI, VC_KBV_SCORE_2, Instant.now()),
                        createVcStoreItem(USER_ID_1, ADDRESS_CRI, VC_ADDRESS, Instant.now()),
                        createVcStoreItem(USER_ID_1, NINO_CRI, VC_NINO_SUCCESSFUL, Instant.now()));

        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P2.name(), emptyContraIndicators);

        // Assert
        JsonNode ninoClaim = credentials.getNinoClaim();
        assertEquals("AA000003D", ninoClaim.get(0).get("personalNumber").asText());
    }

    @Test
    void generateUserIdentityShouldNotSetNinoClaimWhenVotIsP0()
            throws HttpResponseExceptionWithErrorBody, CredentialParseException {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1, DCMAW_CRI, VC_DRIVING_PERMIT_DCMAW, Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, NINO_CRI, VC_NINO_SUCCESSFUL, Instant.now()));

        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);
        mockParamStoreCalls(paramsToMockForP0WithNoCi);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P0.name(), emptyContraIndicators);

        // Assert
        assertNull(credentials.getNinoClaim());
    }

    @Test
    void generateUserIdentityShouldReturnEmptyNinoClaimWhenMissingNinoProperty()
            throws HttpResponseExceptionWithErrorBody, CredentialParseException {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1, DCMAW_CRI, VC_DRIVING_PERMIT_DCMAW, Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, KBV_CRI, VC_KBV_SCORE_2, Instant.now()),
                        createVcStoreItem(USER_ID_1, ADDRESS_CRI, VC_ADDRESS, Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                NINO_CRI,
                                VC_NINO_MISSING_SOCIAL_SECURITY_RECORD,
                                Instant.now()));

        mockParamStoreCalls(paramsToMockForP2);
        mockCredentialIssuerConfig();
        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P2.name(), emptyContraIndicators);

        // Assert
        assertNull(credentials.getNinoClaim());
    }

    @Test
    void generateUserIdentityShouldReturnEmptyNinoClaimWhenMissingNinoVc()
            throws HttpResponseExceptionWithErrorBody, CredentialParseException {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1, DCMAW_CRI, VC_DRIVING_PERMIT_DCMAW, Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, KBV_CRI, VC_KBV_SCORE_2, Instant.now()),
                        createVcStoreItem(USER_ID_1, ADDRESS_CRI, VC_ADDRESS, Instant.now()));

        mockParamStoreCalls(paramsToMockForP2);
        mockCredentialIssuerConfig();
        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P2.name(), emptyContraIndicators);

        // Assert
        assertNull(credentials.getNinoClaim());
    }

    @Test
    void generateUserIdentityShouldReturnEmptyNinoClaimWhenNinoVcIsUnsuccessful()
            throws HttpResponseExceptionWithErrorBody, CredentialParseException {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1, DCMAW_CRI, VC_DRIVING_PERMIT_DCMAW, Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, KBV_CRI, VC_KBV_SCORE_2, Instant.now()),
                        createVcStoreItem(USER_ID_1, ADDRESS_CRI, VC_ADDRESS, Instant.now()),
                        createVcStoreItem(
                                USER_ID_1, NINO_CRI, VC_NINO_UNSUCCESSFUL, Instant.now()));

        mockParamStoreCalls(paramsToMockForP2);
        mockCredentialIssuerConfig();
        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P2.name(), emptyContraIndicators);

        // Assert
        assertNull(credentials.getNinoClaim());
    }

    @Test
    void shouldSetSubClaimOnUserIdentity() throws Exception {
        // Arrange
        mockParamStoreCalls(paramsToMockForP2);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P2.name(), emptyContraIndicators);

        // Assert
        assertEquals("test-sub", credentials.getSub());
    }

    @Test
    void shouldSetVtmClaimOnUserIdentity() throws Exception {
        // Arrange
        mockParamStoreCalls(paramsToMockForP2);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P2.name(), emptyContraIndicators);

        // Assert
        assertEquals("mock-vtm-claim", credentials.getVtm());
    }

    @Test
    void generateUserIdentityShouldSetAddressClaimOnUserIdentity() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                VC_PASSPORT_NON_DCMAW_SUCCESSFUL,
                                Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, KBV_CRI, VC_KBV_SCORE_2, Instant.now()),
                        createVcStoreItem(USER_ID_1, ADDRESS_CRI, VC_ADDRESS_2, Instant.now()));

        mockParamStoreCalls(paramsToMockForP2);
        mockCredentialIssuerConfig();
        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);

        // Act
        UserIdentity userIdentity =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P2.name(), emptyContraIndicators);

        // Assert
        JsonNode userIdentityJsonNode =
                objectMapper.readTree(objectMapper.writeValueAsString(userIdentity));
        JsonNode address = userIdentityJsonNode.get(ADDRESS_CLAIM_NAME).get(0);

        assertEquals("221B", address.get("buildingName").asText());
        assertEquals("BAKER STREET", address.get("streetName").asText());
        assertEquals("LONDON", address.get("addressLocality").asText());
        assertEquals("NW1 6XE", address.get("postalCode").asText());
        assertEquals("1887-01-01", address.get("validFrom").asText());
    }

    @Test
    void generateUserIdentityShouldThrowIfAddressVCIsMissingAddressProperty() {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                VC_PASSPORT_NON_DCMAW_SUCCESSFUL,
                                Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, KBV_CRI, VC_KBV_SCORE_2, Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                ADDRESS_CRI,
                                VC_ADDRESS_MISSING_ADDRESS_PROPERTY,
                                Instant.now()));

        when(mockConfigService.getSsmParameter(CORE_VTM_CLAIM)).thenReturn("mock-vtm-claim");
        mockCredentialIssuerConfig();
        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);

        // Act & Assert
        HttpResponseExceptionWithErrorBody thrownException =
                assertThrows(
                        HttpResponseExceptionWithErrorBody.class,
                        () ->
                                userIdentityService.generateUserIdentity(
                                        USER_ID_1,
                                        "test-sub",
                                        Vot.P2.name(),
                                        emptyContraIndicators));

        assertEquals(500, thrownException.getResponseCode());
        assertEquals(
                ErrorResponse.FAILED_TO_GENERATE_ADDRESS_CLAIM.getCode(),
                thrownException.getErrorBody().get("error"));
        assertEquals(
                ErrorResponse.FAILED_TO_GENERATE_ADDRESS_CLAIM.getMessage(),
                thrownException.getErrorBody().get("error_description"));
    }

    @Test
    void generateUserIdentityShouldThrowIfAddressVCCanNotBeParsed() {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                VC_PASSPORT_NON_DCMAW_SUCCESSFUL,
                                Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, KBV_CRI, VC_KBV_SCORE_2, Instant.now()),
                        createVcStoreItem(USER_ID_1, ADDRESS_CRI, "GARBAGE", Instant.now()));

        when(mockConfigService.getSsmParameter(CORE_VTM_CLAIM)).thenReturn("mock-vtm-claim");
        mockCredentialIssuerConfig();
        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);

        // Act & Assert
        assertThrows(
                CredentialParseException.class,
                () ->
                        userIdentityService.generateUserIdentity(
                                USER_ID_1, "test-sub", Vot.P2.name(), emptyContraIndicators));
    }

    @Test
    void shouldNotSetAddressClaimWhenVotIsP0() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, KBV_CRI, VC_KBV_SCORE_2, Instant.now()),
                        createVcStoreItem(USER_ID_1, ADDRESS_CRI, VC_ADDRESS_2, Instant.now()));

        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);
        mockParamStoreCalls(paramsToMockForP0WithNoCi);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P0.name(), emptyContraIndicators);

        // Assert
        assertNull(credentials.getAddressClaim());
    }

    @Test
    void shouldReturnListOfVcsForSharedAttributes() {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                VC_PASSPORT_NON_DCMAW_SUCCESSFUL,
                                Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()));

        // Act
        List<String> vcList = userIdentityService.getIdentityCredentials(vcStoreItems);

        // Assert
        assertEquals(VC_PASSPORT_NON_DCMAW_SUCCESSFUL, vcList.get(0));
        assertEquals(VC_FRAUD_SCORE_1, vcList.get(1));
    }

    @Test
    void shouldSetDrivingPermitClaimWhenVotIsP2() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1, DCMAW_CRI, VC_DRIVING_PERMIT_DCMAW, Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, ADDRESS_CRI, VC_ADDRESS, Instant.now()));

        mockParamStoreCalls(paramsToMockForP2);
        mockCredentialIssuerConfig();
        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P2.name(), emptyContraIndicators);

        // Assert
        JsonNode drivingPermitClaim = credentials.getDrivingPermitClaim();

        assertEquals("MORGA753116SM9IJ", drivingPermitClaim.get(0).get("personalNumber").asText());
        assertEquals("123456", drivingPermitClaim.get(0).get("issueNumber").asText());
        assertEquals("2023-01-18", drivingPermitClaim.get(0).get("expiryDate").asText());
    }

    @Test
    void shouldNotSetDrivingPermitClaimWhenVotIsP0() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1, DCMAW_CRI, VC_DRIVING_PERMIT_DCMAW, Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, ADDRESS_CRI, VC_ADDRESS, Instant.now()));

        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);
        mockParamStoreCalls(paramsToMockForP0WithNoCi);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P0.name(), emptyContraIndicators);

        // Assert
        JsonNode drivingPermitClaim = credentials.getDrivingPermitClaim();

        assertNull(drivingPermitClaim);
    }

    @Test
    void shouldNotSetDrivingPermitClaimWhenDrivingPermitVCIsMissing() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                VC_PASSPORT_NON_DCMAW_SUCCESSFUL,
                                Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, KBV_CRI, VC_KBV_SCORE_2, Instant.now()),
                        createVcStoreItem(USER_ID_1, ADDRESS_CRI, VC_ADDRESS, Instant.now()));

        mockParamStoreCalls(paramsToMockForP2);
        mockCredentialIssuerConfig();
        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P2.name(), emptyContraIndicators);

        // Assert
        JsonNode drivingPermitClaim = credentials.getDrivingPermitClaim();

        assertNull(drivingPermitClaim);
    }

    @Test
    void shouldNotSetDrivingPermitClaimWhenDrivingPermitVCFailed() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                DCMAW_CRI,
                                VC_DRIVING_PERMIT_DCMAW_FAILED,
                                Instant.now()),
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                VC_PASSPORT_NON_DCMAW_SUCCESSFUL,
                                Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, ADDRESS_CRI, VC_ADDRESS, Instant.now()),
                        createVcStoreItem(USER_ID_1, KBV_CRI, VC_KBV_SCORE_2, Instant.now()));

        mockParamStoreCalls(paramsToMockForP2);
        mockCredentialIssuerConfig();
        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P2.name(), emptyContraIndicators);

        // Assert
        JsonNode drivingPermitClaim = credentials.getDrivingPermitClaim();

        assertNull(drivingPermitClaim);
    }

    @Test
    void shouldReturnEmptyWhenMissingDrivingPermitProperty() throws Exception {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                DCMAW_CRI,
                                VC_DRIVING_PERMIT_DCMAW_MISSING_DRIVING_PERMIT_PROPERTY,
                                Instant.now()));

        mockParamStoreCalls(paramsToMockForP2);
        mockCredentialIssuerConfig();
        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);

        // Act
        UserIdentity credentials =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P2.name(), emptyContraIndicators);

        // Assert
        assertNull(credentials.getDrivingPermitClaim());
    }

    @Test
    void generateUserIdentityShouldThrowIfDcmawVCCanNotBeParsed() {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(
                                USER_ID_1,
                                PASSPORT_CRI,
                                VC_PASSPORT_NON_DCMAW_SUCCESSFUL,
                                Instant.now()),
                        createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, KBV_CRI, VC_KBV_SCORE_2, Instant.now()),
                        createVcStoreItem(USER_ID_1, ADDRESS_CRI, VC_ADDRESS_2, Instant.now()),
                        createVcStoreItem(USER_ID_1, DCMAW_CRI, "GARBAGE", Instant.now()));

        when(mockConfigService.getSsmParameter(CORE_VTM_CLAIM)).thenReturn("mock-vtm-claim");
        mockCredentialIssuerConfig();
        when(mockDataStore.getItems(anyString())).thenReturn(vcStoreItems);

        // Act & Assert
        CredentialParseException thrownException =
                assertThrows(
                        CredentialParseException.class,
                        () ->
                                userIdentityService.generateUserIdentity(
                                        USER_ID_1,
                                        "test-sub",
                                        Vot.P2.name(),
                                        emptyContraIndicators));
        assertEquals(
                "Encountered a parsing error while attempting to parse successful VC Store items.",
                thrownException.getMessage());
    }

    @Test
    void generateUserIdentityShouldSetExitCodeWhenP2AndAlwaysRequiredCiPresent() throws Exception {
        // Arrange
        mockParamStoreCalls(paramsToMockForP2);
        when(mockConfigService.getSsmParameter(RETURN_CODES_ALWAYS_REQUIRED)).thenReturn("🦆,🐧");
        when(mockDataStore.getItems(anyString())).thenReturn(List.of());
        when(mockConfigService.getContraIndicatorConfigMap())
                .thenReturn(
                        Map.of(
                                "X01", new ContraIndicatorConfig("X01", 4, -3, "🦆"),
                                "X02", new ContraIndicatorConfig("X02", 4, -3, "2"),
                                "Z03", new ContraIndicatorConfig("Z03", 4, -3, "3")));

        ContraIndicators contraIndicators =
                ContraIndicators.builder()
                        .contraIndicatorsMap(
                                Map.of(
                                        "X01", ContraIndicator.builder().code("X01").build(),
                                        "X02", ContraIndicator.builder().code("X02").build(),
                                        "Z03", ContraIndicator.builder().code("Z03").build()))
                        .build();

        // Act
        UserIdentity userIdentity =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P2.name(), contraIndicators);

        // Assert
        assertEquals(List.of(new ReturnCode("🦆")), userIdentity.getReturnCode());
    }

    @Test
    void generateUserIdentityShouldSetEmptyExitCodeWhenP2AndAlwaysRequiredCiNotPresent()
            throws Exception {
        // Arrange
        mockParamStoreCalls(paramsToMockForP2);

        // Act
        UserIdentity userIdentity =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P2.name(), emptyContraIndicators);

        assertEquals(List.of(), userIdentity.getReturnCode());
    }

    @Test
    void generateUserIdentityShouldThrowWhenP2AndCiCodeNotFound() {
        // Arrange
        when(mockConfigService.getSsmParameter(CORE_VTM_CLAIM)).thenReturn("mock-vtm-claim");
        when(mockConfigService.getContraIndicatorConfigMap())
                .thenReturn(Map.of("X01", new ContraIndicatorConfig("X01", 4, -3, "1")));

        ContraIndicators contraIndicators =
                ContraIndicators.builder()
                        .contraIndicatorsMap(
                                Map.of("wat", ContraIndicator.builder().code("wat").build()))
                        .build();

        // Act & Assert
        String vot = Vot.P2.name();
        assertThrows(
                UnrecognisedCiException.class,
                () ->
                        userIdentityService.generateUserIdentity(
                                USER_ID_1, "test-sub", vot, contraIndicators));
    }

    @Test
    void generateUserIdentityShouldSetExitCodeWhenBreachingCiThreshold() throws Exception {
        // Arrange
        mockParamStoreCalls(paramsToMockForP0);
        when(mockConfigService.getContraIndicatorConfigMap())
                .thenReturn(
                        Map.of(
                                "X01", new ContraIndicatorConfig("X01", 4, -3, "1"),
                                "X02", new ContraIndicatorConfig("X02", 4, -3, "2"),
                                "Z03", new ContraIndicatorConfig("Z03", 4, -3, "3")));

        ContraIndicators contraIndicators =
                ContraIndicators.builder()
                        .contraIndicatorsMap(
                                Map.of(
                                        "X01", ContraIndicator.builder().code("X01").build(),
                                        "X02",
                                                ContraIndicator.builder()
                                                        .code("X02")
                                                        .mitigation(
                                                                List.of(
                                                                        Mitigation.builder()
                                                                                .code("M01")
                                                                                .build()))
                                                        .build(),
                                        "Z03", ContraIndicator.builder().code("Z03").build()))
                        .build();

        // Act
        UserIdentity userIdentity =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P0.name(), contraIndicators);

        // Assert
        assertEquals(
                List.of(new ReturnCode("1"), new ReturnCode("2"), new ReturnCode("3")),
                userIdentity.getReturnCode());
    }

    @Test
    void generateUserIdentityShouldThrowWhenBreachingAndCiCodeNotFound() {
        // Arrange
        when(mockConfigService.getSsmParameter(CORE_VTM_CLAIM)).thenReturn("mock-vtm-claim");
        when(mockConfigService.getContraIndicatorConfigMap())
                .thenReturn(Map.of("X01", new ContraIndicatorConfig("X01", 4, -3, "1")));

        ContraIndicators contraIndicators =
                ContraIndicators.builder()
                        .contraIndicatorsMap(
                                Map.of("wat", ContraIndicator.builder().code("wat").build()))
                        .build();
        String vot = Vot.P0.name();
        assertThrows(
                UnrecognisedCiException.class,
                () ->
                        userIdentityService.generateUserIdentity(
                                USER_ID_1, "test-sub", vot, contraIndicators));
    }

    @Test
    void generateUserIdentityShouldDeduplicateExitCodes() throws Exception {
        // Arrange
        mockParamStoreCalls(paramsToMockForP0);
        when(mockConfigService.getContraIndicatorConfigMap())
                .thenReturn(
                        Map.of(
                                "X01", new ContraIndicatorConfig("X01", 4, -3, "1"),
                                "X02", new ContraIndicatorConfig("X02", 4, -3, "2"),
                                "Z03", new ContraIndicatorConfig("Z03", 4, -3, "3"),
                                "Z04", new ContraIndicatorConfig("Z04", 4, -3, "2")));

        ContraIndicators contraIndicators =
                ContraIndicators.builder()
                        .contraIndicatorsMap(
                                Map.of(
                                        "X01", ContraIndicator.builder().code("X01").build(),
                                        "X02", ContraIndicator.builder().code("X02").build(),
                                        "Z03", ContraIndicator.builder().code("Z03").build(),
                                        "Z04", ContraIndicator.builder().code("Z04").build()))
                        .build();

        // Act
        UserIdentity userIdentity =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P0.name(), contraIndicators);

        // Assert
        assertEquals(
                List.of(new ReturnCode("1"), new ReturnCode("2"), new ReturnCode("3")),
                userIdentity.getReturnCode());
    }

    @Test
    void generateUserIdentityShouldSetRequiredExitCodeWhenP0AndNotBreachingCiThreshold()
            throws Exception {
        // Arrange
        when(mockConfigService.getSsmParameter(CORE_VTM_CLAIM)).thenReturn("mock-vtm-claim");
        when(mockConfigService.getSsmParameter(CI_SCORING_THRESHOLD)).thenReturn("10");
        when(mockConfigService.getSsmParameter(RETURN_CODES_NON_CI_BREACHING_P0)).thenReturn("🐧");

        when(mockConfigService.getContraIndicatorConfigMap())
                .thenReturn(Map.of("X01", new ContraIndicatorConfig("X01", 4, -3, "1")));

        ContraIndicators contraIndicators =
                ContraIndicators.builder()
                        .contraIndicatorsMap(
                                Map.of("X01", ContraIndicator.builder().code("X01").build()))
                        .build();

        // Act
        UserIdentity userIdentity =
                userIdentityService.generateUserIdentity(
                        USER_ID_1, "test-sub", Vot.P0.name(), contraIndicators);

        // Assert
        assertEquals(List.of(new ReturnCode("🐧")), userIdentity.getReturnCode());
        verify(mockConfigService, never()).getSsmParameter(RETURN_CODES_ALWAYS_REQUIRED);
    }

    @Test
    void checkNamesForCorrelationValidateSpecialCharactersSuccessScenarios() {
        List<String> fullNames = List.of("Alice JANE DOE", "AlIce Ja-ne Do-e", "ALiCE JA'-ne Do'e");
        assertTrue(userIdentityService.checkNamesForCorrelation(fullNames));

        fullNames = List.of("SÖŞMİĞë", "sosmige", "SÖŞ-Mİ'Ğe");
        assertTrue(userIdentityService.checkNamesForCorrelation(fullNames));
    }

    @Test
    void checkNamesForCorrelationValidateSpecialCharactersFailScenarios() {
        List<String> fullNames = List.of("Alice JANE DOE", "Alce JANE DOE", "Alëce JANE DOE");
        assertFalse(userIdentityService.checkNamesForCorrelation(fullNames));

        fullNames = List.of("Alice JANE DOE", "Alce JANE DOE");
        assertFalse(userIdentityService.checkNamesForCorrelation(fullNames));

        fullNames = List.of("Alice JANE DOE", "JANE AlIce DOE");
        assertFalse(userIdentityService.checkNamesForCorrelation(fullNames));

        fullNames = List.of("Alice JANE DOE", "Alice JANE Onel");
        assertFalse(userIdentityService.checkNamesForCorrelation(fullNames));
    }

    @Test
    void isVcSuccessfulShouldThrowIfNoStatusFoundForIssuer() {
        // Arrange
        List<VcStatusDto> vcStatusDtos =
                List.of(
                        new VcStatusDto("issuer1", true),
                        new VcStatusDto("issuer2", true),
                        new VcStatusDto("issuer3", true));

        // Act & Assert
        assertThrows(
                NoVcStatusForIssuerException.class,
                () -> userIdentityService.isVcSuccessful(vcStatusDtos, "badIssuer"));
    }

    @Test
    void getCredentialsWithSingleCredentialAndOnlyOneValidEvidence() {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(createVcStoreItem(USER_ID_1, BAV_CRI, M1B_DCMAW_VC, Instant.now()));
        claimedIdentityConfig.setRequiresAdditionalEvidence(true);
        when(mockConfigService.getOauthCriActiveConnectionConfig(any()))
                .thenReturn(claimedIdentityConfig);

        // Act & Assert
        assertTrue(userIdentityService.checkRequiresAdditionalEvidence(vcStoreItems));
    }

    @Test
    void
            getCredentialsWithSingleCredentialWithOnlyOneValidEvidenceAndRequiresAdditionalEvidencesFalse() {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(createVcStoreItem(USER_ID_1, BAV_CRI, M1B_DCMAW_VC, Instant.now()));
        claimedIdentityConfig.setRequiresAdditionalEvidence(false);
        when(mockConfigService.getOauthCriActiveConnectionConfig(any()))
                .thenReturn(claimedIdentityConfig);

        // Act & Assert
        assertFalse(userIdentityService.checkRequiresAdditionalEvidence(vcStoreItems));
    }

    @Test
    void getCredentialsWithMultipleCredentialsAndAllValidEvidence() {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(USER_ID_1, BAV_CRI, M1B_DCMAW_VC, Instant.now()),
                        createVcStoreItem(USER_ID_1, F2F_CRI, M1A_F2F_VC, Instant.now()));

        // Act & Assert
        assertFalse(userIdentityService.checkRequiresAdditionalEvidence(vcStoreItems));
    }

    @Test
    void getCredentialsWithMultipleCredentialsAndAllInValidEvidence() {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(USER_ID_1, BAV_CRI, VC_FRAUD_SCORE_1, Instant.now()),
                        createVcStoreItem(USER_ID_1, F2F_CRI, VC_KBV_SCORE_2, Instant.now()));

        // Act & Assert
        assertFalse(userIdentityService.checkRequiresAdditionalEvidence(vcStoreItems));
    }

    @Test
    void getCredentialsWithMultipleCredentialsAndValidAndInValidEvidence() {
        // Arrange
        List<VcStoreItem> vcStoreItems =
                List.of(
                        createVcStoreItem(USER_ID_1, BAV_CRI, M1B_DCMAW_VC, Instant.now()),
                        createVcStoreItem(USER_ID_1, F2F_CRI, VC_KBV_SCORE_2, Instant.now()));

        claimedIdentityConfig.setRequiresAdditionalEvidence(true);
        when(mockConfigService.getOauthCriActiveConnectionConfig(any()))
                .thenReturn(claimedIdentityConfig);

        // Act & Assert
        assertTrue(userIdentityService.checkRequiresAdditionalEvidence(vcStoreItems));
    }

    private VcStoreItem createVcStoreItem(
            String userId, String credentialIssuer, String credential, Instant dateCreated) {
        VcStoreItem vcStoreItem = new VcStoreItem();
        vcStoreItem.setUserId(userId);
        vcStoreItem.setCredentialIssuer(credentialIssuer);
        vcStoreItem.setCredential(credential);
        vcStoreItem.setDateCreated(dateCreated);
        vcStoreItem.setExpirationTime(dateCreated.plusSeconds(1000L));
        return vcStoreItem;
    }

    private void mockCredentialIssuerConfig() {
        NON_EVIDENCE_CRI_TYPES.forEach(
                credentialIssuer ->
                        when(mockConfigService.getComponentId(credentialIssuer))
                                .thenReturn(credentialIssuer));
    }

    private void mockParamStoreCalls(Map<ConfigurationVariable, String> params) {
        params.forEach(
                (key, value) -> when(mockConfigService.getSsmParameter(key)).thenReturn(value));
    }

    private String createCredentialWithNameAndBirthDate(
            String givenName, String familyName, String birthDate) throws Exception {
        var birthDateList = new ArrayList<String>();
        birthDateList.add(birthDate);
        return createCredentialWithNameAndBirthDate(
                givenName, null, familyName, birthDateList, true);
    }

    private String createCredentialWithNameAndBirthDate(
            String givenName, String middleName, String familyName, String birthDate)
            throws Exception {
        var birthDateList = new ArrayList<String>();
        birthDateList.add(birthDate);
        return createCredentialWithNameAndBirthDate(
                givenName, middleName, familyName, birthDateList, true);
    }

    private String createCredentialWithNameAndBirthDate(
            String givenName, String familyName, String birthDate, boolean isSuccessful)
            throws Exception {
        var birthDateList = new ArrayList<String>();
        birthDateList.add(birthDate);
        return createCredentialWithNameAndBirthDate(
                givenName, null, familyName, birthDateList, isSuccessful);
    }

    private String createCredentialWithNameAndBirthDate(
            String givenName, String familyName, List<String> birthDates) throws Exception {
        return createCredentialWithNameAndBirthDate(givenName, null, familyName, birthDates, true);
    }

    private String createCredentialWithNameAndBirthDate(
            String givenName,
            String middleName,
            String familyName,
            List<String> birthDates,
            boolean isSuccessful)
            throws Exception {
        var credentialSubject = new HashMap<String, Object>();
        var vcClaim = new HashMap<String, Object>();

        vcClaim.put(VC_CREDENTIAL_SUBJECT, credentialSubject);
        List<NameParts> nameParts =
                new ArrayList<>(
                        List.of(
                                new NameParts(givenName, VC_GIVEN_NAME),
                                new NameParts(familyName, VC_FAMILY_NAME)));
        if (middleName != null) {
            nameParts.add(1, new NameParts(middleName, VC_GIVEN_NAME));
        }

        credentialSubject.put(VC_NAME, List.of(new Name(nameParts)));

        credentialSubject.put(VC_BIRTH_DATE, birthDates.stream().map(BirthDate::new).toList());

        JWTClaimsSet claims =
                new JWTClaimsSet.Builder()
                        .claim(VC_CLAIM, vcClaim)
                        .issuer(
                                // address VC are always considered "successful" even without
                                // evidence
                                isSuccessful ? ADDRESS_CRI : PASSPORT_CRI)
                        .build();

        SignedJWT signedJWT = new SignedJWT(JWS_HEADER, claims);
        signedJWT.sign(jwtSigner);

        return signedJWT.serialize();
    }

    @Test
    void findIdentityReturnsIdentityClaimWhenEvidenceCheckIsFalse()
            throws HttpResponseExceptionWithErrorBody, CredentialParseException {
        VcStoreItem vcStoreItem =
                createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now());
        List<VcStoreItem> vcStoreItems = new ArrayList<>();
        vcStoreItems.add(vcStoreItem);
        Optional<IdentityClaim> result = userIdentityService.findIdentityClaim(vcStoreItems, false);
        assertTrue(result.isPresent());
        assertEquals("Chris", result.get().getFullName());
    }

    @Test
    void findIdentityDoesNotReturnsIdentityClaimWhenEvidenceCheckIsTrue()
            throws HttpResponseExceptionWithErrorBody, CredentialParseException {
        VcStoreItem vcStoreItem =
                createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_SCORE_1, Instant.now());
        List<VcStoreItem> vcStoreItems = new ArrayList<>();
        vcStoreItems.add(vcStoreItem);
        Optional<IdentityClaim> result = userIdentityService.findIdentityClaim(vcStoreItems, true);
        assertTrue(result.isEmpty());
    }

    @Test
    void findIdentityThrowsHttpResponseExceptionWithErrorBodyWhenNoNamePresent() {
        VcStoreItem vcStoreItem =
                createVcStoreItem(USER_ID_1, FRAUD_CRI, VC_FRAUD_WITHOUT_NAME, Instant.now());
        List<VcStoreItem> vcStoreItems = new ArrayList<>();
        vcStoreItems.add(vcStoreItem);
        assertThrows(
                HttpResponseExceptionWithErrorBody.class,
                () -> userIdentityService.findIdentityClaim(vcStoreItems, false));
    }
}
