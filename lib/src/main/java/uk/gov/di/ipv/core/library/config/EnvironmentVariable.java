package uk.gov.di.ipv.core.library.config;

public enum EnvironmentVariable {
    BEARER_TOKEN_TTL,
    CLIENT_AUTH_JWT_IDS_TABLE_NAME,
    CREDENTIAL_ISSUERS_CONFIG_PARAM_PREFIX,
    ENVIRONMENT,
    IPV_SESSIONS_TABLE_NAME,
    CLIENT_OAUTH_SESSIONS_TABLE_NAME,
    IS_LOCAL,
    USER_ISSUED_CREDENTIALS_TABLE_NAME,
    SIGNING_KEY_ID_PARAM,
    SQS_AUDIT_EVENT_QUEUE_URL,
    CI_STORAGE_PUT_LAMBDA_ARN,
    CI_STORAGE_POST_MITIGATIONS_LAMBDA_ARN,
    CI_STORAGE_GET_LAMBDA_ARN,
}
