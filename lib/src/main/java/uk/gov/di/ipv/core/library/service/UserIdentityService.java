package uk.gov.di.ipv.core.library.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.Gson;
import com.nimbusds.jose.shaded.json.JSONArray;
import com.nimbusds.jose.shaded.json.JSONObject;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.StringMapMessage;
import uk.gov.di.ipv.core.library.annotations.ExcludeFromGeneratedCoverageReport;
import uk.gov.di.ipv.core.library.domain.BirthDate;
import uk.gov.di.ipv.core.library.domain.DebugCredentialAttributes;
import uk.gov.di.ipv.core.library.domain.ErrorResponse;
import uk.gov.di.ipv.core.library.domain.IdentityClaim;
import uk.gov.di.ipv.core.library.domain.Name;
import uk.gov.di.ipv.core.library.domain.UserIdentity;
import uk.gov.di.ipv.core.library.domain.UserIssuedDebugCredential;
import uk.gov.di.ipv.core.library.domain.VectorOfTrust;
import uk.gov.di.ipv.core.library.dto.CredentialIssuerConfig;
import uk.gov.di.ipv.core.library.dto.VcStatusDto;
import uk.gov.di.ipv.core.library.exceptions.HttpResponseExceptionWithErrorBody;
import uk.gov.di.ipv.core.library.helpers.LogHelper;
import uk.gov.di.ipv.core.library.persistence.DataStore;
import uk.gov.di.ipv.core.library.persistence.item.VcStoreItem;

import java.text.ParseException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.BACKEND_SESSION_TIMEOUT;
import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.CORE_VTM_CLAIM;
import static uk.gov.di.ipv.core.library.config.EnvironmentVariable.USER_ISSUED_CREDENTIALS_TABLE_NAME;
import static uk.gov.di.ipv.core.library.domain.VerifiableCredentialConstants.VC_CLAIM;
import static uk.gov.di.ipv.core.library.domain.VerifiableCredentialConstants.VC_CREDENTIAL_SUBJECT;
import static uk.gov.di.ipv.core.library.domain.VerifiableCredentialConstants.VC_EVIDENCE;

public class UserIdentityService {
    public static final String NAME_PROPERTY_NAME = "name";
    public static final String BIRTH_DATE_PROPERTY_NAME = "birthDate";
    public static final String ADDRESS_PROPERTY_NAME = "address";
    public static final List<String> ADDRESS_CRI_TYPES =
            List.of(ADDRESS_PROPERTY_NAME, "stubAddress");
    public static final List<String> PASSPORT_CRI_TYPES =
            List.of("ukPassport", "stubUkPassport", "dcmaw", "stubDcmaw");
    public static final List<String> DRIVING_PERMIT_CRI_TYPES = List.of("dcmaw", "stubDcmaw");
    public static final List<String> EVIDENCE_CRI_TYPES =
            List.of("ukPassport", "stubUkPassport", "dcmaw", "stubDcmaw");

    private static final Logger LOGGER = LogManager.getLogger();
    private static final String PASSPORT_PROPERTY_NAME = "passport";
    private static final String DRIVING_PERMIT_PROPERTY_NAME = "drivingPermit";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ConfigurationService configurationService;
    private final DataStore<VcStoreItem> dataStore;

    @ExcludeFromGeneratedCoverageReport
    public UserIdentityService(ConfigurationService configurationService) {
        this.configurationService = configurationService;
        boolean isRunningLocally = this.configurationService.isRunningLocally();
        this.dataStore =
                new DataStore<>(
                        this.configurationService.getEnvironmentVariable(
                                USER_ISSUED_CREDENTIALS_TABLE_NAME),
                        VcStoreItem.class,
                        DataStore.getClient(isRunningLocally),
                        isRunningLocally,
                        configurationService);
    }

    public UserIdentityService(
            ConfigurationService configurationService,
            DataStore<VcStoreItem> dataStore) {
        this.configurationService = configurationService;
        this.dataStore = dataStore;
    }

    public List<String> getUserIssuedCredentials(String userId) {
        List<VcStoreItem> credentialIssuerItems = dataStore.getItems(userId);

        return credentialIssuerItems.stream()
                .map(VcStoreItem::getCredential)
                .collect(Collectors.toList());
    }

    public void deleteVcStoreItemsIfAnyExpired(String userId) {
        Instant nowPlusSessionTimeout =
                Instant.now()
                        .plusSeconds(
                                Long.parseLong(
                                        configurationService.getSsmParameter(
                                                BACKEND_SESSION_TIMEOUT)));
        List<VcStoreItem> expiredVcStoreItems =
                this.dataStore.getItemsWithAttributeLessThanOrEqualValue(
                        userId, "expirationTime", nowPlusSessionTimeout.toString());
        if (!expiredVcStoreItems.isEmpty()) {
            LOGGER.info("Found VCs due to expire within session timeout");
            deleteVcStoreItems(userId);
        }
    }

    public void deleteVcStoreItems(String userId) {
        List<VcStoreItem> vcStoreItems = dataStore.getItems(userId);
        if (!vcStoreItems.isEmpty()) {
            var message =
                    new StringMapMessage()
                            .with("description", "Deleting existing issued VCs")
                            .with(
                                    LogHelper.LogField.NUMBER_OF_VCS.getFieldName(),
                                    String.valueOf(vcStoreItems.size()));
            LOGGER.info(message);
        }
        for (VcStoreItem item : vcStoreItems) {
            dataStore.delete(item.getUserId(), item.getCredentialIssuer());
        }
    }

    public List<VcStoreItem> getVcStoreItems(String userId) {
        return dataStore.getItems(userId);
    }

    public VcStoreItem getVcStoreItem(String userId, String criId) {
        return dataStore.getItem(userId, criId);
    }

    public UserIdentity generateUserIdentity(
            String userId, String sub, String vot, List<VcStatusDto> currentVcStatuses)
            throws HttpResponseExceptionWithErrorBody {
        List<VcStoreItem> credentialIssuerItems = dataStore.getItems(userId);

        List<String> vcJwts =
                credentialIssuerItems.stream()
                        .map(VcStoreItem::getCredential)
                        .collect(Collectors.toList());

        String vtm = configurationService.getSsmParameter(CORE_VTM_CLAIM);

        UserIdentity.Builder userIdentityBuilder =
                new UserIdentity.Builder().setVcs(vcJwts).setSub(sub).setVot(vot).setVtm(vtm);

        if (vot.equals(VectorOfTrust.P2.toString())) {
            Optional<IdentityClaim> identityClaim =
                    generateIdentityClaim(credentialIssuerItems, currentVcStatuses);
            identityClaim.ifPresent(userIdentityBuilder::setIdentityClaim);

            Optional<JsonNode> addressClaim = generateAddressClaim(credentialIssuerItems);
            addressClaim.ifPresent(userIdentityBuilder::setAddressClaim);

            Optional<JsonNode> passportClaim =
                    generatePassportClaim(credentialIssuerItems, currentVcStatuses);
            passportClaim.ifPresent(userIdentityBuilder::setPassportClaim);

            Optional<JsonNode> drivingPermitClaim =
                    generateDrivingPermitClaim(credentialIssuerItems, currentVcStatuses);
            drivingPermitClaim.ifPresent(userIdentityBuilder::setDrivingPermitClaim);
        }

        return userIdentityBuilder.build();
    }

    public Map<String, String> getUserIssuedDebugCredentials(String userId) {
        List<VcStoreItem> credentialIssuerItems = dataStore.getItems(userId);
        Map<String, String> userIssuedDebugCredentials = new HashMap<>();
        Gson gson = new Gson();

        credentialIssuerItems.forEach(
                criItem -> {
                    DebugCredentialAttributes attributes =
                            new DebugCredentialAttributes(
                                    criItem.getUserId(), criItem.getDateCreated().toString());
                    UserIssuedDebugCredential debugCredential =
                            new UserIssuedDebugCredential(attributes);

                    try {
                        JWTClaimsSet jwtClaimsSet =
                                SignedJWT.parse(criItem.getCredential()).getJWTClaimsSet();
                        JSONObject vcClaim = (JSONObject) jwtClaimsSet.getClaim(VC_CLAIM);
                        if (vcClaim == null || isNotPopulatedJsonArray(vcClaim.get(VC_EVIDENCE))) {
                            LOGGER.error("Evidence not found in verifiable credential");
                        } else {
                            JSONArray evidenceArray = ((JSONArray) vcClaim.get(VC_EVIDENCE));
                            debugCredential.setEvidence((Map<String, Object>) evidenceArray.get(0));
                        }
                    } catch (ParseException e) {
                        LOGGER.error("Failed to parse credential JSON for the debug page");
                    }

                    String debugCredentialJson = gson.toJson(debugCredential);

                    userIssuedDebugCredentials.put(
                            criItem.getCredentialIssuer(), debugCredentialJson);
                });

        return userIssuedDebugCredentials;
    }

    private Optional<IdentityClaim> generateIdentityClaim(
            List<VcStoreItem> credentialIssuerItems,
            List<VcStatusDto> currentVcStatuses)
            throws HttpResponseExceptionWithErrorBody {
        for (VcStoreItem item : credentialIssuerItems) {
            CredentialIssuerConfig credentialIssuerConfig =
                    configurationService.getCredentialIssuer(item.getCredentialIssuer());
            if (EVIDENCE_CRI_TYPES.contains(item.getCredentialIssuer())
                    && isVcSuccessful(
                            currentVcStatuses, credentialIssuerConfig.getAudienceForClients())) {
                try {
                    JsonNode nameNode =
                            objectMapper
                                    .readTree(
                                            SignedJWT.parse(item.getCredential())
                                                    .getPayload()
                                                    .toString())
                                    .path(VC_CLAIM)
                                    .path(VC_CREDENTIAL_SUBJECT)
                                    .path(NAME_PROPERTY_NAME);

                    if (nameNode.isMissingNode()) {
                        LOGGER.error("Name property is missing from passport VC");
                        throw new HttpResponseExceptionWithErrorBody(
                                500, ErrorResponse.FAILED_TO_GENERATE_IDENTIY_CLAIM);
                    }

                    JsonNode birthDateNode =
                            objectMapper
                                    .readTree(
                                            SignedJWT.parse(item.getCredential())
                                                    .getPayload()
                                                    .toString())
                                    .path(VC_CLAIM)
                                    .path(VC_CREDENTIAL_SUBJECT)
                                    .path(BIRTH_DATE_PROPERTY_NAME);

                    if (birthDateNode.isMissingNode()) {
                        LOGGER.error("BirthDate property is missing from passport VC");
                        throw new HttpResponseExceptionWithErrorBody(
                                500, ErrorResponse.FAILED_TO_GENERATE_IDENTIY_CLAIM);
                    }

                    List<Name> names =
                            objectMapper.treeToValue(
                                    nameNode,
                                    objectMapper
                                            .getTypeFactory()
                                            .constructCollectionType(List.class, Name.class));
                    List<BirthDate> birthDates =
                            objectMapper.treeToValue(
                                    birthDateNode,
                                    objectMapper
                                            .getTypeFactory()
                                            .constructCollectionType(List.class, BirthDate.class));

                    return Optional.of(new IdentityClaim(names, birthDates));
                } catch (ParseException | JsonProcessingException e) {
                    LOGGER.error("Failed to parse VC JWT because: {}", e.getMessage());
                    throw new HttpResponseExceptionWithErrorBody(
                            500, ErrorResponse.FAILED_TO_GENERATE_IDENTIY_CLAIM);
                }
            }
        }
        LOGGER.warn("Failed to generate identity claim");
        return Optional.empty();
    }

    private Optional<JsonNode> generateAddressClaim(
            List<VcStoreItem> credentialIssuerItems)
            throws HttpResponseExceptionWithErrorBody {
        Optional<VcStoreItem> addressCredentialItem =
                credentialIssuerItems.stream()
                        .filter(
                                credential ->
                                        ADDRESS_CRI_TYPES.contains(
                                                credential.getCredentialIssuer()))
                        .findFirst();

        if (addressCredentialItem.isPresent()) {
            JsonNode addressNode;
            try {
                addressNode =
                        objectMapper
                                .readTree(
                                        SignedJWT.parse(addressCredentialItem.get().getCredential())
                                                .getPayload()
                                                .toString())
                                .path(VC_CLAIM)
                                .path(VC_CREDENTIAL_SUBJECT)
                                .path(ADDRESS_PROPERTY_NAME);
                if (addressNode.isMissingNode()) {
                    LOGGER.error("Address property is missing from address VC");
                    throw new HttpResponseExceptionWithErrorBody(
                            500, ErrorResponse.FAILED_TO_GENERATE_ADDRESS_CLAIM);
                }
            } catch (JsonProcessingException | ParseException e) {
                LOGGER.error("Error while parsing Address CRI credential: '{}'", e.getMessage());
                throw new HttpResponseExceptionWithErrorBody(
                        HttpStatus.SC_INTERNAL_SERVER_ERROR,
                        ErrorResponse.FAILED_TO_GENERATE_ADDRESS_CLAIM);
            }
            return Optional.of(addressNode);
        }
        LOGGER.warn("Failed to find Address CRI credential");
        return Optional.empty();
    }

    private Optional<JsonNode> generatePassportClaim(
            List<VcStoreItem> credentialIssuerItems,
            List<VcStatusDto> currentVcStatuses)
            throws HttpResponseExceptionWithErrorBody {
        for (VcStoreItem item : credentialIssuerItems) {
            CredentialIssuerConfig credentialIssuerConfig =
                    configurationService.getCredentialIssuer(item.getCredentialIssuer());
            if (PASSPORT_CRI_TYPES.contains(item.getCredentialIssuer())
                    && isVcSuccessful(
                            currentVcStatuses, credentialIssuerConfig.getAudienceForClients())) {
                JsonNode passportNode;
                try {
                    passportNode =
                            objectMapper
                                    .readTree(
                                            SignedJWT.parse(item.getCredential())
                                                    .getPayload()
                                                    .toString())
                                    .path(VC_CLAIM)
                                    .path(VC_CREDENTIAL_SUBJECT)
                                    .path(PASSPORT_PROPERTY_NAME);
                    if (passportNode.isMissingNode()) {
                        StringMapMessage mapMessage =
                                new StringMapMessage()
                                        .with("message", "Passport property is missing from VC")
                                        .with("criIss", item.getCredentialIssuer());
                        LOGGER.warn(mapMessage);

                        return Optional.empty();
                    }
                } catch (JsonProcessingException | ParseException e) {
                    LOGGER.error(
                            "Error while parsing Passport CRI credential: '{}'", e.getMessage());
                    throw new HttpResponseExceptionWithErrorBody(
                            HttpStatus.SC_INTERNAL_SERVER_ERROR,
                            ErrorResponse.FAILED_TO_GENERATE_PASSPORT_CLAIM);
                }
                return Optional.of(passportNode);
            }
        }

        LOGGER.warn("Failed to find Passport CRI credential");
        return Optional.empty();
    }

    private Optional<JsonNode> generateDrivingPermitClaim(
            List<VcStoreItem> credentialIssuerItems,
            List<VcStatusDto> currentVcStatuses)
            throws HttpResponseExceptionWithErrorBody {
        for (VcStoreItem item : credentialIssuerItems) {
            CredentialIssuerConfig credentialIssuerConfig =
                    configurationService.getCredentialIssuer(item.getCredentialIssuer());
            if (DRIVING_PERMIT_CRI_TYPES.contains(item.getCredentialIssuer())
                    && isVcSuccessful(
                            currentVcStatuses, credentialIssuerConfig.getAudienceForClients())) {
                JsonNode drivingPermitNode;
                try {
                    drivingPermitNode =
                            objectMapper
                                    .readTree(
                                            SignedJWT.parse(item.getCredential())
                                                    .getPayload()
                                                    .toString())
                                    .path(VC_CLAIM)
                                    .path(VC_CREDENTIAL_SUBJECT)
                                    .path(DRIVING_PERMIT_PROPERTY_NAME);

                    if (drivingPermitNode instanceof ArrayNode) {
                        ((ObjectNode) drivingPermitNode.get(0)).remove("fullAddress");
                        ((ObjectNode) drivingPermitNode.get(0)).remove("issueDate");
                    }

                    if (drivingPermitNode.isMissingNode()) {
                        StringMapMessage mapMessage =
                                new StringMapMessage()
                                        .with(
                                                "message",
                                                "Driving Permit property is missing from VC")
                                        .with("criIss", item.getCredentialIssuer());
                        LOGGER.warn(mapMessage);

                        return Optional.empty();
                    }

                    return Optional.of(drivingPermitNode);
                } catch (ParseException | JsonProcessingException e) {
                    LOGGER.error(
                            "Error while parsing Driving Permit CRI credential: '{}'",
                            e.getMessage());
                    throw new HttpResponseExceptionWithErrorBody(
                            HttpStatus.SC_INTERNAL_SERVER_ERROR,
                            ErrorResponse.FAILED_TO_GENERATE_DRIVING_PERMIT_CLAIM);
                }
            }
        }
        LOGGER.warn("Failed to find Driving Permit CRI credential");
        return Optional.empty();
    }

    private boolean isNotPopulatedJsonArray(Object input) {
        return !(input instanceof JSONArray)
                || ((JSONArray) input).isEmpty()
                || !(((JSONArray) input).get(0) instanceof JSONObject);
    }

    public boolean isVcSuccessful(List<VcStatusDto> currentVcStatuses, String criIss) {
        return currentVcStatuses.stream()
                .filter(vcStatusDto -> vcStatusDto.getCriIss().equals(criIss))
                .findFirst()
                .orElseThrow()
                .getIsSuccessfulVc();
    }

    public List<String> getUserIssuedCredentialIssuers(String userId) {
        List<VcStoreItem> credentialIssuerItems = dataStore.getItems(userId);

        return credentialIssuerItems.stream()
                .map(VcStoreItem::getCredentialIssuer)
                .collect(Collectors.toList());
    }
}
