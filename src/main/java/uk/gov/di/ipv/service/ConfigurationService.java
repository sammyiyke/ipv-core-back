package uk.gov.di.ipv.service;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.lambda.powertools.parameters.ParamManager;
import software.amazon.lambda.powertools.parameters.SSMProvider;
import uk.gov.di.ipv.dto.CredentialIssuers;
import uk.gov.di.ipv.helpers.CredentialIssuerLoader;

import java.net.URI;
import java.util.Optional;

public class ConfigurationService {

    private static final long DEFAULT_BEARER_TOKEN_TTL_IN_SECS = 3600L;
    private static final String LOCALHOST_URI = "http://localhost:4567";

    private final SSMProvider ssmProvider;

    public ConfigurationService(SSMProvider ssmProvider) {
        this.ssmProvider = ssmProvider;
    }

    public ConfigurationService() {
        if (Boolean.parseBoolean(System.getenv("IS_LOCAL"))) {
            this.ssmProvider =
                    ParamManager.getSsmProvider(
                            SsmClient.builder()
                                    .endpointOverride(URI.create(LOCALHOST_URI))
                                    .region(Region.EU_WEST_2)
                                    .build());
        } else {
            this.ssmProvider = ParamManager.getSsmProvider();
        }
    }

    public boolean isRunningLocally() {
        return Boolean.parseBoolean(System.getenv("IS_LOCAL"));
    }

    public String getAuthCodesTableName() {
        return System.getenv("AUTH_CODES_TABLE_NAME");
    }

    public CredentialIssuers getCredentialIssuers() {
        return CredentialIssuerLoader.loadCredentialIssuers(
                ssmProvider.get(System.getenv("CREDENTIAL_ISSUER_CONFIG_PARAMETER_STORE_KEY")));
    }

    public String getUserIssuedCredentialTableName() {
        return System.getenv("USER_ISSUED_CREDENTIALS_TABLE_NAME");
    }

    public String getAccessTokensTableName() {
        return System.getenv("ACCESS_TOKENS_TABLE_NAME");
    }

    public String getIpvSessionTableName() {
        return System.getenv("IPV_SESSIONS_TABLE_NAME");
    }

    public long getBearerAccessTokenTtl() {
        return Optional.ofNullable(System.getenv("BEARER_TOKEN_TTL"))
                .map(Long::valueOf)
                .orElse(DEFAULT_BEARER_TOKEN_TTL_IN_SECS);
    }
}
