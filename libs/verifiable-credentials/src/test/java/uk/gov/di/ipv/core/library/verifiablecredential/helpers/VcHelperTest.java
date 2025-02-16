package uk.gov.di.ipv.core.library.verifiablecredential.helpers;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.di.ipv.core.library.domain.ProfileType;
import uk.gov.di.ipv.core.library.domain.VerifiableCredential;
import uk.gov.di.ipv.core.library.dto.OauthCriConfig;
import uk.gov.di.ipv.core.library.enums.Vot;
import uk.gov.di.ipv.core.library.exceptions.CredentialParseException;
import uk.gov.di.ipv.core.library.exceptions.UnrecognisedVotException;
import uk.gov.di.ipv.core.library.gpg45.domain.CredentialEvidenceItem.EvidenceType;
import uk.gov.di.ipv.core.library.service.ConfigService;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.FRAUD_CHECK_EXPIRY_PERIOD_HOURS;
import static uk.gov.di.ipv.core.library.domain.CriConstants.ADDRESS_CRI;
import static uk.gov.di.ipv.core.library.domain.CriConstants.CLAIMED_IDENTITY_CRI;
import static uk.gov.di.ipv.core.library.fixtures.TestFixtures.VC_RESIDENCE_PERMIT_DCMAW;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.M1A_ADDRESS_VC;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.M1A_EXPERIAN_FRAUD_VC;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.M1B_DCMAW_VC;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.PASSPORT_NON_DCMAW_SUCCESSFUL_VC;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.vcDrivingPermit;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.vcEmptyEvidence;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.vcExperianFraudFailed;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.vcExperianFraudScoreOne;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.vcF2fM1a;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.vcFraudExpired;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.vcFraudNotExpired;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.vcHmrcMigration;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.vcInvalidVot;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.vcNinoSuccessful;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.vcNullVot;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.vcPassportInvalidBirthDate;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.vcPassportM1aFailed;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.vcPassportM1aMissingEvidence;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.vcPassportM1aWithCI;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.vcPassportMissingBirthDate;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.vcTicf;
import static uk.gov.di.ipv.core.library.fixtures.VcFixtures.vcVerificationM1a;

@ExtendWith(MockitoExtension.class)
class VcHelperTest {
    @Mock private ConfigService configService;
    private static OauthCriConfig addressConfig = null;
    private static OauthCriConfig claimedIdentityConfig = null;

    static {
        try {
            addressConfig = createOauthCriConfig("https://review-a.integration.account.gov.uk");
            claimedIdentityConfig =
                    createOauthCriConfig("https://review-c.integration.account.gov.uk");
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

    private static Stream<Arguments> SuccessfulTestCases() {
        return Stream.of(
                Arguments.of("Non-evidence VC", M1A_ADDRESS_VC),
                Arguments.of("Evidence VC", PASSPORT_NON_DCMAW_SUCCESSFUL_VC),
                Arguments.of("Evidence VC with CI", vcPassportM1aWithCI()),
                Arguments.of("Fraud and activity VC", M1A_EXPERIAN_FRAUD_VC),
                Arguments.of("Verification VC", vcVerificationM1a()),
                Arguments.of("Verification DCMAW VC", M1B_DCMAW_VC),
                Arguments.of("Verification F2F VC", vcF2fM1a()),
                Arguments.of("Verification Nino VC", vcNinoSuccessful()),
                Arguments.of("Verification TICF VC", vcTicf()));
    }

    @ParameterizedTest
    @MethodSource("SuccessfulTestCases")
    void shouldIdentifySuccessfulVc(String name, VerifiableCredential vc) throws Exception {
        mockCredentialIssuerConfig();

        assertTrue(VcHelper.isSuccessfulVc(vc), name);
    }

    @ParameterizedTest
    @MethodSource("UnsuccessfulTestCases")
    void shouldIdentifyUnsuccessfulVcs(String name, VerifiableCredential vc) throws Exception {
        mockCredentialIssuerConfig();

        assertFalse(VcHelper.isSuccessfulVc(vc), name);
    }

    @Test
    void shouldFilterVCsBasedOnProfileType_GPG45() throws Exception {
        var vcs =
                List.of(
                        PASSPORT_NON_DCMAW_SUCCESSFUL_VC,
                        vcExperianFraudScoreOne(),
                        vcTicf(),
                        vcHmrcMigration());
        assertEquals(3, VcHelper.filterVCBasedOnProfileType(vcs, ProfileType.GPG45).size());
    }

    @Test
    void shouldFilterVCsBasedOnProfileType_operational() throws Exception {
        var vcs =
                List.of(
                        PASSPORT_NON_DCMAW_SUCCESSFUL_VC,
                        vcExperianFraudScoreOne(),
                        vcHmrcMigration());
        assertEquals(
                1, VcHelper.filterVCBasedOnProfileType(vcs, ProfileType.OPERATIONAL_HMRC).size());
    }

    @Test
    void shouldExtractTxIdFromCredentials() {
        var txns = VcHelper.extractTxnIdsFromCredentials(List.of(vcNinoSuccessful()));

        assertEquals(1, txns.size());
        assertEquals("e5b22348-c866-4b25-bb50-ca2106af7874", txns.get(0));
    }

    @Test
    void shouldExtractAgeFromCredential() {
        assertNotNull(VcHelper.extractAgeFromCredential(PASSPORT_NON_DCMAW_SUCCESSFUL_VC));
    }

    @Test
    void shouldExtractAgeFromCredentialWithMissingBirthDate() {
        assertNull(VcHelper.extractAgeFromCredential(vcPassportMissingBirthDate()));
    }

    @Test
    void shouldExtractAgeFromCredentialWithInvalidBirthDate() {
        assertNull(VcHelper.extractAgeFromCredential(vcPassportInvalidBirthDate()));
    }

    @Test
    void shouldChkIfDocUKIssuedForCredential() {
        assertEquals(
                Boolean.TRUE,
                VcHelper.checkIfDocUKIssuedForCredential(PASSPORT_NON_DCMAW_SUCCESSFUL_VC));
    }

    @Test
    void shouldCheckIfDocUKIssuedForCredentialForDL() {
        assertEquals(Boolean.TRUE, VcHelper.checkIfDocUKIssuedForCredential(vcDrivingPermit()));
    }

    @Test
    void shouldCheckIfDocUKIssuedForCredentialForResidencePermit()
            throws ParseException, CredentialParseException {
        assertEquals(
                Boolean.TRUE,
                VcHelper.checkIfDocUKIssuedForCredential(
                        VerifiableCredential.fromValidJwt(
                                null, null, SignedJWT.parse(VC_RESIDENCE_PERMIT_DCMAW))));
    }

    @Test
    void shouldCheckIfDocUKIssuedForCredentialForDCMAW() {
        assertEquals(Boolean.TRUE, VcHelper.checkIfDocUKIssuedForCredential(vcDrivingPermit()));
    }

    @Test
    void shouldCheckIsItOperationalVC() throws Exception {
        assertTrue(VcHelper.isOperationalProfileVc(vcHmrcMigration()));
        assertFalse(VcHelper.isOperationalProfileVc(PASSPORT_NON_DCMAW_SUCCESSFUL_VC));
    }

    @Test
    void shouldGetVcVot() throws Exception {
        assertEquals(Vot.PCL250, VcHelper.getVcVot(vcHmrcMigration()));
    }

    @Test
    void shouldThrowUnrecognisedVotExceptionIfInvalidVcVot() {
        assertThrows(UnrecognisedVotException.class, () -> VcHelper.getVcVot(vcInvalidVot()));
    }

    @Test
    void shouldReturnNullIfVcVotIsNotPresent() throws Exception {
        assertNull(VcHelper.getVcVot(vcNullVot()));
    }

    @Test
    void shouldReturnEmptyListIfEvidenceTypeIsValidAndNotPresent() {
        var vcs = List.of(vcPassportM1aFailed());
        // Call the method under test
        List<VerifiableCredential> result =
                VcHelper.filterVCBasedOnEvidenceType(vcs, EvidenceType.IDENTITY_FRAUD);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnListIfEvidenceTypeIsValidAndPresentForFiltering() {
        var vcs = List.of(PASSPORT_NON_DCMAW_SUCCESSFUL_VC, vcExperianFraudScoreOne(), vcTicf());
        // Call the method under test
        List<VerifiableCredential> result =
                VcHelper.filterVCBasedOnEvidenceType(vcs, EvidenceType.IDENTITY_FRAUD);

        // Assert Result
        assertFalse(result.isEmpty());
        assertEquals(1, result.size());
    }

    @Test
    void shouldReturnFalseIfEmptyEvidenceType() throws Exception {
        var vcs = List.of(vcEmptyEvidence());
        // Call the method under test
        List<VerifiableCredential> result =
                VcHelper.filterVCBasedOnEvidenceType(vcs, EvidenceType.IDENTITY_FRAUD);

        // Assert Result
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnTrueWhenVcIsExpired() throws Exception {
        VcHelper.setConfigService(configService);
        // Arrange
        VerifiableCredential vc = vcFraudExpired();
        when(configService.getSsmParameter(FRAUD_CHECK_EXPIRY_PERIOD_HOURS)).thenReturn("1");

        // Act
        boolean result = VcHelper.isExpiredFraudVc(vc);

        // Assert
        assertTrue(result);
    }

    @Test
    void shouldReturnFalseWhenVcIsNotExpired() throws Exception {
        VcHelper.setConfigService(configService);
        // Arrange
        VerifiableCredential vc = vcFraudNotExpired();
        when(configService.getSsmParameter(FRAUD_CHECK_EXPIRY_PERIOD_HOURS)).thenReturn("1");

        // Act
        boolean result = VcHelper.isExpiredFraudVc(vc);

        // Assert
        assertFalse(result);
    }

    private static Stream<Arguments> UnsuccessfulTestCases() {
        return Stream.of(
                Arguments.of("VC missing evidence", vcPassportM1aMissingEvidence()),
                Arguments.of("Failed passport VC", vcPassportM1aFailed()),
                Arguments.of("Failed fraud check", vcExperianFraudFailed()));
    }

    private void mockCredentialIssuerConfig() {
        VcHelper.setConfigService(configService);
        when(configService.getComponentId(ADDRESS_CRI)).thenReturn(addressConfig.getComponentId());
        when(configService.getComponentId(CLAIMED_IDENTITY_CRI))
                .thenReturn(claimedIdentityConfig.getComponentId());
    }

    private static OauthCriConfig createOauthCriConfig(String componentId)
            throws URISyntaxException {
        return OauthCriConfig.builder()
                .tokenUrl(new URI("http://example.com/token"))
                .credentialUrl(new URI("http://example.com/credential"))
                .authorizeUrl(new URI("http://example.com/authorize"))
                .clientId("ipv-core")
                .signingKey("test-jwk")
                .encryptionKey("test-encryption-jwk")
                .componentId(componentId)
                .clientCallbackUrl(new URI("http://example.com/redirect"))
                .requiresApiKey(true)
                .requiresAdditionalEvidence(false)
                .build();
    }
}
