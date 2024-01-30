package uk.gov.di.ipv.core.revokevcs;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.shaded.json.JSONObject;
import com.nimbusds.jwt.SignedJWT;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.tracing.Tracing;
import uk.gov.di.ipv.core.library.annotations.ExcludeFromGeneratedCoverageReport;
import uk.gov.di.ipv.core.library.auditing.AuditEvent;
import uk.gov.di.ipv.core.library.auditing.AuditEventTypes;
import uk.gov.di.ipv.core.library.auditing.AuditEventUser;
import uk.gov.di.ipv.core.library.auditing.extension.AuditExtensionErrorMessage;
import uk.gov.di.ipv.core.library.auditing.extension.AuditExtensionsVcEvidence;
import uk.gov.di.ipv.core.library.config.ConfigurationVariable;
import uk.gov.di.ipv.core.library.config.EnvironmentVariable;
import uk.gov.di.ipv.core.library.exceptions.SqsException;
import uk.gov.di.ipv.core.library.helpers.LogHelper;
import uk.gov.di.ipv.core.library.persistence.DataStore;
import uk.gov.di.ipv.core.library.persistence.item.VcStoreItem;
import uk.gov.di.ipv.core.library.service.AuditService;
import uk.gov.di.ipv.core.library.service.ConfigService;
import uk.gov.di.ipv.core.library.verifiablecredential.service.VerifiableCredentialService;
import uk.gov.di.ipv.core.revokevcs.domain.RevokeRequest;
import uk.gov.di.ipv.core.revokevcs.domain.UserIdCriIdPair;
import uk.gov.di.ipv.core.revokevcs.exceptions.RevokeVcException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.ParseException;
import java.util.List;

@ExcludeFromGeneratedCoverageReport
@SuppressWarnings("unused") // Temporarily disable to pass sonarqube
public class RevokeVcsHandler implements RequestStreamHandler {
    private static final Logger LOGGER = LogManager.getLogger();
    private final ConfigService configService;
    private final VerifiableCredentialService verifiableCredentialService;
    private final DataStore<VcStoreItem> archivedVcDataStore;
    private final AuditService auditService;

    @SuppressWarnings("unused") // Used by AWS
    public RevokeVcsHandler(
            ConfigService configService,
            VerifiableCredentialService verifiableCredentialService,
            DataStore<VcStoreItem> archivedVcDataStore,
            AuditService auditService) {
        this.configService = configService;
        this.verifiableCredentialService = verifiableCredentialService;
        this.archivedVcDataStore = archivedVcDataStore;
        this.auditService = auditService;
    }

    @SuppressWarnings("unused") // Used through dependency injection
    public RevokeVcsHandler() {
        this.configService = new ConfigService();
        this.verifiableCredentialService = new VerifiableCredentialService(configService);
        boolean isRunningLocally = this.configService.isRunningLocally();
        this.archivedVcDataStore =
                new DataStore<>(
                        this.configService.getEnvironmentVariable(
                                EnvironmentVariable.REVOKED_USER_CREDENTIALS_TABLE_NAME),
                        VcStoreItem.class,
                        DataStore.getClient(isRunningLocally),
                        isRunningLocally,
                        configService);
        this.auditService = new AuditService(AuditService.getDefaultSqsClient(), configService);
    }

    @Override
    @Tracing
    @Logging(clearState = true)
    public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context)
            throws IOException {
        LogHelper.attachComponentIdToLogs(configService);
        List<UserIdCriIdPair> userIdCriIdPairs =
                new ObjectMapper()
                        .readValue(inputStream, RevokeRequest.class)
                        .getUserIdCriIdPairs();
        LOGGER.info(
                LogHelper.buildLogMessage(
                        String.format("Revoking %s VCs.", userIdCriIdPairs.size())));

        try {
            revoke(userIdCriIdPairs);
            LOGGER.info(
                    LogHelper.buildLogMessage(
                            String.format(
                                    "Finished attempt to revoke %s VCs.",
                                    userIdCriIdPairs.size())));
        } catch (SqsException e) {
            LOGGER.error(
                    LogHelper.buildLogMessage(
                            String.format(
                                    "Stopped revoking VCs because of failure to send audit event: %s",
                                    e.getMessage())));
        }
    }

    private void revoke(List<UserIdCriIdPair> userIdCriIdPairs) throws SqsException {
        var numberOfVcs = userIdCriIdPairs.size();
        for (int i = 0; i < numberOfVcs; i++) {
            var userIdCriIdPair = userIdCriIdPairs.get(i);
            LOGGER.info(
                    LogHelper.buildLogMessage(
                            String.format("Revoking VC (%s / %s)", i + 1, numberOfVcs)));

            try {
                revoke(userIdCriIdPair, i, numberOfVcs);
                LOGGER.info(
                        LogHelper.buildLogMessage(
                                String.format(
                                        "Successfully revoked VC (%s / %s)", i + 1, numberOfVcs)));
            } catch (Exception e) {
                LOGGER.error(
                        LogHelper.buildLogMessage(
                                String.format(
                                        "Unexpected error occurred (%s / %s): %s",
                                        i + 1, numberOfVcs, e.getMessage())));
                sendRevokedFailureAuditEvent(
                        userIdCriIdPair.getUserId(), e.getMessage(), i, numberOfVcs);
            }
        }
    }

    private void revoke(UserIdCriIdPair userIdCriIdPair, int i, int numberOfVcs) throws Exception {
        // Read KBV VC for the ID
        VcStoreItem vcStoreItem =
                this.verifiableCredentialService.getVcStoreItem(
                        userIdCriIdPair.getUserId(), userIdCriIdPair.getCriId());

        if (vcStoreItem != null) {
            // Archive VC
            archivedVcDataStore.create(vcStoreItem);

            // Submit audit event
            sendVcRevokedAuditEvent(userIdCriIdPair.getUserId(), vcStoreItem, i, numberOfVcs);

            // Delete VC from the main table
            // ...

        } else {
            throw new RevokeVcException("VC cannot be found");
        }
    }

    private void sendVcRevokedAuditEvent(
            String userId, VcStoreItem vcStoreItem, int i, int numberOfVcs)
            throws ParseException, SqsException, JsonProcessingException {
        var auditEventUser = new AuditEventUser(userId, null, null, null);

        var signedCredential = SignedJWT.parse(vcStoreItem.getCredential());
        var jwtClaimsSet = signedCredential.getJWTClaimsSet();
        var vc = (JSONObject) jwtClaimsSet.getClaim("vc");
        var evidence = vc.getAsString("evidence");

        var auditExtensions = new AuditExtensionsVcEvidence(jwtClaimsSet.getIssuer(), evidence);
        var auditEvent =
                new AuditEvent(
                        AuditEventTypes.IPV_VC_REVOKED,
                        configService.getSsmParameter(ConfigurationVariable.COMPONENT_ID),
                        auditEventUser,
                        auditExtensions);
        LOGGER.debug(
                LogHelper.buildLogMessage(
                        String.format(
                                "Sending audit event IPV_VC_REVOKED (%s / %s)",
                                i + 1, numberOfVcs)));
        auditService.sendAuditEvent(auditEvent);
    }

    private void sendRevokedFailureAuditEvent(
            String userId, String errorMessage, int i, int numberOfVcs) throws SqsException {
        var auditEventUser = new AuditEventUser(userId, null, null, null);

        var auditExtensions = new AuditExtensionErrorMessage(errorMessage);
        var auditEvent =
                new AuditEvent(
                        AuditEventTypes.IPV_VC_REVOKED_FAILURE,
                        configService.getSsmParameter(ConfigurationVariable.COMPONENT_ID),
                        auditEventUser,
                        auditExtensions);
        LOGGER.debug(
                LogHelper.buildLogMessage(
                        String.format(
                                "Sending audit event IPV_VC_REVOKED_FAILURE (%s / %s)",
                                i + 1, numberOfVcs)));
        try {
            auditService.sendAuditEvent(auditEvent);
        } catch (SqsException e) {
            throw new SqsException(
                    String.format(
                            "Failed to send audit event IPV_VC_REVOKED_FAILURE (%s / %s)",
                            i + 1, numberOfVcs),
                    e);
        }
    }
}
