package uk.gov.di.ipv.core.library.verifiablecredential.service;

import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.gov.di.ipv.core.library.annotations.ExcludeFromGeneratedCoverageReport;
import uk.gov.di.ipv.core.library.config.EnvironmentVariable;
import uk.gov.di.ipv.core.library.domain.CoiSubjourneyType;
import uk.gov.di.ipv.core.library.domain.ErrorResponse;
import uk.gov.di.ipv.core.library.domain.VerifiableCredential;
import uk.gov.di.ipv.core.library.exceptions.CredentialParseException;
import uk.gov.di.ipv.core.library.exceptions.VerifiableCredentialException;
import uk.gov.di.ipv.core.library.helpers.LogHelper;
import uk.gov.di.ipv.core.library.persistence.DataStore;
import uk.gov.di.ipv.core.library.persistence.item.SessionCredentialItem;
import uk.gov.di.ipv.core.library.service.ConfigService;

import java.util.ArrayList;
import java.util.List;

import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.SESSION_CREDENTIALS_TTL;
import static uk.gov.di.ipv.core.library.domain.CoiSubjourneyType.ADDRESS_ONLY;
import static uk.gov.di.ipv.core.library.domain.CoiSubjourneyType.FAMILY_NAME_ONLY;
import static uk.gov.di.ipv.core.library.domain.CoiSubjourneyType.GIVEN_NAMES_ONLY;
import static uk.gov.di.ipv.core.library.domain.CriConstants.ADDRESS_CRI;
import static uk.gov.di.ipv.core.library.domain.CriConstants.EXPERIAN_FRAUD_CRI;

public class SessionCredentialsService {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String RECEIVED_THIS_SESSION = "receivedThisSession";
    private final DataStore<SessionCredentialItem> dataStore;

    public SessionCredentialsService(DataStore<SessionCredentialItem> dataStore) {
        this.dataStore = dataStore;
    }

    @ExcludeFromGeneratedCoverageReport
    public SessionCredentialsService(ConfigService configService) {
        this.dataStore =
                new DataStore<>(
                        configService.getEnvironmentVariable(
                                EnvironmentVariable.SESSION_CREDENTIALS_TABLE_NAME),
                        SessionCredentialItem.class,
                        DataStore.getClient(),
                        configService);
    }

    public List<VerifiableCredential> getCredentials(String ipvSessionId, String userId)
            throws VerifiableCredentialException {
        return getCredentials(ipvSessionId, userId, null);
    }

    public List<VerifiableCredential> getCredentials(
            String ipvSessionId, String userId, Boolean receivedThisSession)
            throws VerifiableCredentialException {
        try {
            var verifiableCredentialList = new ArrayList<VerifiableCredential>();
            var credentials =
                    receivedThisSession != null
                            ? dataStore.getItemsWithBooleanAttribute(
                                    ipvSessionId, RECEIVED_THIS_SESSION, receivedThisSession)
                            : dataStore.getItems(ipvSessionId);
            for (var credential : credentials) {
                verifiableCredentialList.add(
                        VerifiableCredential.fromSessionCredentialItem(credential, userId));
            }

            return verifiableCredentialList;
        } catch (CredentialParseException e) {
            LOGGER.error(LogHelper.buildErrorMessage("Error parsing session credential item", e));
            throw new VerifiableCredentialException(
                    HTTPResponse.SC_SERVER_ERROR, ErrorResponse.FAILED_TO_PARSE_ISSUED_CREDENTIALS);
        } catch (Exception e) {
            LOGGER.error(LogHelper.buildErrorMessage("Error getting session credentials", e));
            throw new VerifiableCredentialException(
                    HTTPResponse.SC_SERVER_ERROR, ErrorResponse.FAILED_TO_GET_CREDENTIAL);
        }
    }

    public void persistCredentials(
            List<VerifiableCredential> credentials,
            String ipvSessionId,
            boolean receivedThisSession)
            throws VerifiableCredentialException {
        try {
            for (var credential : credentials) {
                dataStore.create(
                        credential.toSessionCredentialItem(ipvSessionId, receivedThisSession),
                        SESSION_CREDENTIALS_TTL);
            }
        } catch (Exception e) {
            LOGGER.error(LogHelper.buildErrorMessage("Error persisting session credential", e));
            throw new VerifiableCredentialException(
                    HTTPResponse.SC_SERVER_ERROR, ErrorResponse.FAILED_TO_SAVE_CREDENTIAL);
        }
    }

    public void deleteSessionCredentialsForSubjourneyType(
            String ipvSessionId, CoiSubjourneyType coiSubjourneyType)
            throws VerifiableCredentialException {
        try {
            List<SessionCredentialItem> sessionCredentialItems = dataStore.getItems(ipvSessionId);

            List<SessionCredentialItem> vcsToDelete;

            if (isAddressOnlyJourney(coiSubjourneyType)) {
                vcsToDelete =
                        sessionCredentialItems.stream()
                                .filter(
                                        item ->
                                                List.of(ADDRESS_CRI, EXPERIAN_FRAUD_CRI)
                                                        .contains(item.getCriId()))
                                .toList();

            } else if (isNameOnlyJourney(coiSubjourneyType)) {
                vcsToDelete =
                        sessionCredentialItems.stream()
                                .filter(item -> !item.getCriId().equals(ADDRESS_CRI))
                                .toList();
            } else {
                // if name change & address OR not a COJ journey then delete all session VCs
                vcsToDelete = sessionCredentialItems;
            }

            dataStore.delete(vcsToDelete);
        } catch (Exception e) {
            LOGGER.error(
                    LogHelper.buildErrorMessage(
                            String.format(
                                    "Error deleting session credentials for subjourney: %s",
                                    coiSubjourneyType),
                            e));
            throw new VerifiableCredentialException(
                    HTTPResponse.SC_SERVER_ERROR, ErrorResponse.FAILED_TO_DELETE_CREDENTIAL);
        }
    }

    public void deleteSessionCredentials(String ipvSessionId) throws VerifiableCredentialException {
        LOGGER.info(
                LogHelper.buildLogMessage(
                        "Deleting credentials for current session from session credentials table"));
        try {
            dataStore.deleteAllByPartition(ipvSessionId);
        } catch (Exception e) {
            LOGGER.error(LogHelper.buildErrorMessage("Error deleting session credentials", e));
            throw new VerifiableCredentialException(
                    HTTPResponse.SC_SERVER_ERROR, ErrorResponse.FAILED_TO_DELETE_CREDENTIAL);
        }
    }

    public void deleteSessionCredentialsForCri(String ipvSessionId, String criId)
            throws VerifiableCredentialException {
        try {
            var deleted = dataStore.delete(dataStore.getItemsBySortKeyPrefix(ipvSessionId, criId));
            LOGGER.info(
                    LogHelper.buildLogMessage(
                            String.format(
                                    "Deleted %d credentials for %s from session credentials table",
                                    deleted.size(), criId)));
        } catch (Exception e) {
            LOGGER.error(
                    LogHelper.buildErrorMessage(
                            String.format("Error deleting session credentials for CRI: %s", criId),
                            e));
            throw new VerifiableCredentialException(
                    HTTPResponse.SC_SERVER_ERROR, ErrorResponse.FAILED_TO_DELETE_CREDENTIAL);
        }
    }

    private static boolean isNameOnlyJourney(CoiSubjourneyType coiSubjourneyType) {
        return coiSubjourneyType != null
                && List.of(GIVEN_NAMES_ONLY, FAMILY_NAME_ONLY).contains(coiSubjourneyType);
    }

    private static boolean isAddressOnlyJourney(CoiSubjourneyType coiSubjourneyType) {
        return ADDRESS_ONLY.equals(coiSubjourneyType);
    }
}
