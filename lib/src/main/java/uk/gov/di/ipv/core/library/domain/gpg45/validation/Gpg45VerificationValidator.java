package uk.gov.di.ipv.core.library.domain.gpg45.validation;

import uk.gov.di.ipv.core.library.annotations.ExcludeFromGeneratedCoverageReport;
import uk.gov.di.ipv.core.library.domain.gpg45.Gpg45Profile;
import uk.gov.di.ipv.core.library.domain.gpg45.domain.CredentialEvidenceItem;

public class Gpg45VerificationValidator {
    @ExcludeFromGeneratedCoverageReport
    private Gpg45VerificationValidator() {
        throw new IllegalStateException("Utility class");
    }

    public static boolean validate(CredentialEvidenceItem item, Gpg45Profile gpg45Profile) {
        return item.getVerificationScore() >= gpg45Profile.scores.verification();
    }

    public static boolean isSuccessful(CredentialEvidenceItem item) {
        return item.getVerificationScore() != 0;
    }
}
