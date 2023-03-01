package uk.gov.di.ipv.core.library.service;

import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.AWSLambdaClientBuilder;
import com.amazonaws.services.lambda.model.InvokeRequest;
import com.amazonaws.services.lambda.model.InvokeResult;
import com.google.gson.Gson;
import com.nimbusds.jwt.SignedJWT;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.StringMapMessage;
import uk.gov.di.ipv.core.library.domain.ContraIndicatorItem;
import uk.gov.di.ipv.core.library.domain.GetCiRequest;
import uk.gov.di.ipv.core.library.domain.GetCiResponse;
import uk.gov.di.ipv.core.library.domain.PostCiMitigationRequest;
import uk.gov.di.ipv.core.library.domain.PutCiRequest;
import uk.gov.di.ipv.core.library.exceptions.CiPostMitigationsException;
import uk.gov.di.ipv.core.library.exceptions.CiPutException;
import uk.gov.di.ipv.core.library.exceptions.CiRetrievalException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static uk.gov.di.ipv.core.library.config.EnvironmentVariable.CI_STORAGE_GET_LAMBDA_ARN;
import static uk.gov.di.ipv.core.library.config.EnvironmentVariable.CI_STORAGE_POST_MITIGATIONS_LAMBDA_ARN;
import static uk.gov.di.ipv.core.library.config.EnvironmentVariable.CI_STORAGE_PUT_LAMBDA_ARN;

public class CiStorageService {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson gson = new Gson();
    private static final String FAILED_LAMBDA_MESSAGE = "Lambda execution failed";
    private final AWSLambda lambdaClient;
    private final ConfigService configService;

    public CiStorageService(ConfigService configService) {
        this.lambdaClient = AWSLambdaClientBuilder.defaultClient();
        this.configService = configService;
    }

    public CiStorageService(AWSLambda lambdaClient, ConfigService configService) {
        this.lambdaClient = lambdaClient;
        this.configService = configService;
    }

    public void submitVC(
            SignedJWT verifiableCredential, String govukSigninJourneyId, String ipAddress)
            throws CiPutException {
        InvokeRequest request =
                new InvokeRequest()
                        .withFunctionName(
                                configService.getEnvironmentVariable(CI_STORAGE_PUT_LAMBDA_ARN))
                        .withPayload(
                                gson.toJson(
                                        new PutCiRequest(
                                                govukSigninJourneyId,
                                                ipAddress,
                                                verifiableCredential.serialize())));

        LOGGER.info("Sending VC to CI storage system");
        InvokeResult result = lambdaClient.invoke(request);

        if (lambdaExecutionFailed(result)) {
            logLambdaExecutionError(result);
            throw new CiPutException(FAILED_LAMBDA_MESSAGE);
        }
    }

    public void submitMitigatingVcList(
            List<String> verifiableCredentialList, String govukSigninJourneyId, String ipAddress)
            throws CiPostMitigationsException {
        InvokeRequest request =
                new InvokeRequest()
                        .withFunctionName(
                                configService.getEnvironmentVariable(
                                        CI_STORAGE_POST_MITIGATIONS_LAMBDA_ARN))
                        .withPayload(
                                gson.toJson(
                                        new PostCiMitigationRequest(
                                                govukSigninJourneyId,
                                                ipAddress,
                                                verifiableCredentialList)));

        LOGGER.info("Sending mitigating VC's to CI storage system");
        InvokeResult result = lambdaClient.invoke(request);

        if (lambdaExecutionFailed(result)) {
            logLambdaExecutionError(result);
            throw new CiPostMitigationsException(FAILED_LAMBDA_MESSAGE);
        }
    }

    public List<ContraIndicatorItem> getCIs(
            String userId, String govukSigninJourneyId, String ipAddress)
            throws CiRetrievalException {
        InvokeRequest request =
                new InvokeRequest()
                        .withFunctionName(
                                configService.getEnvironmentVariable(CI_STORAGE_GET_LAMBDA_ARN))
                        .withPayload(
                                gson.toJson(
                                        new GetCiRequest(govukSigninJourneyId, ipAddress, userId)));

        LOGGER.info("Retrieving CIs from CI storage system");
        InvokeResult result = lambdaClient.invoke(request);

        if (lambdaExecutionFailed(result)) {
            logLambdaExecutionError(result);
            throw new CiRetrievalException(FAILED_LAMBDA_MESSAGE);
        }

        String jsonResponse = new String(result.getPayload().array(), StandardCharsets.UTF_8);
        GetCiResponse response = gson.fromJson(jsonResponse, GetCiResponse.class);
        return response.getContraIndicators();
    }

    private boolean lambdaExecutionFailed(InvokeResult result) {
        return result.getStatusCode() != HttpStatus.SC_OK || result.getFunctionError() != null;
    }

    private String getPayloadOrNull(InvokeResult result) {
        ByteBuffer payload = result.getPayload();
        return payload == null ? null : new String(payload.array(), StandardCharsets.UTF_8);
    }

    private void logLambdaExecutionError(InvokeResult result) {
        HashMap<String, String> message = new HashMap<>();
        message.put("message", "CI storage lambda execution failed");
        message.put("error", result.getFunctionError());
        message.put("statusCode", String.valueOf(result.getStatusCode()));
        message.put("payload", getPayloadOrNull(result));
        message.values().removeAll(Collections.singleton(null));
        LOGGER.error(new StringMapMessage(message));
    }
}
