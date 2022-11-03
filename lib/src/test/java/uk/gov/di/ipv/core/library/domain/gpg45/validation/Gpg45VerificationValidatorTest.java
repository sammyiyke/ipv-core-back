package uk.gov.di.ipv.core.library.domain.gpg45.validation;

import org.junit.jupiter.api.Test;
import uk.gov.di.ipv.core.library.domain.ContraIndicatorScores;
import uk.gov.di.ipv.core.library.domain.gpg45.domain.CredentialEvidenceItem;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Gpg45VerificationValidatorTest {
    @Test
    void isSuccessfulShouldReturnTrueOnValidCredential() {
        CredentialEvidenceItem credentialEvidenceItem =
                new CredentialEvidenceItem(
                        CredentialEvidenceItem.EvidenceType.VERIFICATION,
                        2,
                        Collections.emptyList());

        assertTrue(
                Gpg45VerificationValidator.isSuccessful(
                        credentialEvidenceItem, Collections.emptyMap(), 0));
    }

    @Test
    void isSuccessfulShouldReturnTrueOnValidCredentialAndNullCi() {
        CredentialEvidenceItem credentialEvidenceItem =
                new CredentialEvidenceItem(
                        CredentialEvidenceItem.EvidenceType.VERIFICATION, 2, null);

        assertTrue(
                Gpg45VerificationValidator.isSuccessful(
                        credentialEvidenceItem, Collections.emptyMap(), 0));
    }

    @Test
    void isSuccessfulShouldReturnFalseOnValidCredential() {
        CredentialEvidenceItem credentialEvidenceItem =
                new CredentialEvidenceItem(
                        CredentialEvidenceItem.EvidenceType.VERIFICATION,
                        0,
                        Collections.emptyList());

        assertFalse(
                Gpg45VerificationValidator.isSuccessful(
                        credentialEvidenceItem, Collections.emptyMap(), 0));
    }

    @Test
    void isSuccessfulShouldReturnTrueIfCredentialContainsCiWithinScoreThreshold() {
        CredentialEvidenceItem credentialEvidenceItem =
                new CredentialEvidenceItem(
                        CredentialEvidenceItem.EvidenceType.VERIFICATION, 2, List.of("D02"));
        Map<String, ContraIndicatorScores> scoresMap = new HashMap<>();
        scoresMap.put("D02", new ContraIndicatorScores("D02", 50, 0, null));

        assertTrue(Gpg45VerificationValidator.isSuccessful(credentialEvidenceItem, scoresMap, 50));
    }

    @Test
    void isSuccessfulShouldReturnFalseIfCredentialContainsCiExceedingScoreThreshold() {
        CredentialEvidenceItem credentialEvidenceItem =
                new CredentialEvidenceItem(
                        CredentialEvidenceItem.EvidenceType.VERIFICATION, 2, List.of("D02"));
        Map<String, ContraIndicatorScores> scoresMap = new HashMap<>();
        scoresMap.put("D02", new ContraIndicatorScores("D02", 55, 0, null));

        assertFalse(Gpg45VerificationValidator.isSuccessful(credentialEvidenceItem, scoresMap, 50));
    }
}
