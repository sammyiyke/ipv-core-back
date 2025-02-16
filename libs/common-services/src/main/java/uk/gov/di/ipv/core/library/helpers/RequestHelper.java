package uk.gov.di.ipv.core.library.helpers;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.nimbusds.oauth2.sdk.util.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.StringMapMessage;
import uk.gov.di.ipv.core.library.domain.ErrorResponse;
import uk.gov.di.ipv.core.library.domain.JourneyRequest;
import uk.gov.di.ipv.core.library.domain.ProcessRequest;
import uk.gov.di.ipv.core.library.exceptions.HttpResponseExceptionWithErrorBody;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static com.nimbusds.oauth2.sdk.http.HTTPResponse.SC_BAD_REQUEST;
import static software.amazon.awssdk.utils.StringUtils.isBlank;
import static uk.gov.di.ipv.core.library.helpers.LogHelper.LogField.LOG_MESSAGE_DESCRIPTION;

public class RequestHelper {

    public static final String IPV_SESSION_ID_HEADER = "ipv-session-id";
    public static final String IP_ADDRESS_HEADER = "ip-address";
    public static final String FEATURE_SET_HEADER = "feature-set";
    public static final String IS_USER_INITIATED = "isUserInitiated";
    public static final String DELETE_ONLY_GPG45_VCS = "deleteOnlyGPG45VCs";
    private static final Logger LOGGER = LogManager.getLogger();

    private RequestHelper() {}

    public static String getHeaderByKey(Map<String, String> headers, String headerKey) {
        if (Objects.isNull(headers)) {
            return null;
        }
        var values =
                headers.entrySet().stream()
                        .filter(e -> headerKey.equalsIgnoreCase(e.getKey()))
                        .map(Map.Entry::getValue)
                        .toList();
        if (values.size() == 1) {
            var value = values.get(0);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    public static String getIpvSessionId(APIGatewayProxyRequestEvent event)
            throws HttpResponseExceptionWithErrorBody {
        return getIpvSessionId(event.getHeaders());
    }

    public static String getIpvSessionId(JourneyRequest event)
            throws HttpResponseExceptionWithErrorBody {
        return getIpvSessionId(event, false);
    }

    public static String getIpvSessionIdAllowBlank(JourneyRequest event)
            throws HttpResponseExceptionWithErrorBody {
        return getIpvSessionId(event, true);
    }

    public static String getIpAddress(APIGatewayProxyRequestEvent event)
            throws HttpResponseExceptionWithErrorBody {
        return getIpAddress(event.getHeaders());
    }

    public static String getIpAddress(JourneyRequest request)
            throws HttpResponseExceptionWithErrorBody {
        String ipAddress = request.getIpAddress();
        validateIpAddress(ipAddress, "ipAddress not present in request.");
        return ipAddress;
    }

    public static String getClientOAuthSessionId(JourneyRequest event) {
        String clientSessionId = event.getClientOAuthSessionId();
        StringMapMessage message =
                new StringMapMessage()
                        .with(
                                LOG_MESSAGE_DESCRIPTION.getFieldName(),
                                "Client session id missing in header.");
        validateClientOAuthSessionId(clientSessionId, message);
        return StringUtils.isBlank(clientSessionId) ? null : clientSessionId;
    }

    public static List<String> getFeatureSet(JourneyRequest request) {
        String featureSet = request.getFeatureSet();
        List<String> featureSetList =
                (featureSet != null && !featureSet.isBlank())
                        ? Arrays.asList(featureSet.split(","))
                        : Collections.emptyList();
        LogHelper.attachFeatureSetToLogs(featureSetList);
        return featureSetList;
    }

    public static List<String> getFeatureSet(APIGatewayProxyRequestEvent event) {
        return getFeatureSet(event.getHeaders());
    }

    public static List<String> getFeatureSet(Map<String, String> headers) {
        String featureSetHeaderValue = RequestHelper.getHeaderByKey(headers, FEATURE_SET_HEADER);
        List<String> featureSet =
                (featureSetHeaderValue != null)
                        ? Arrays.asList(featureSetHeaderValue.split(","))
                        : Collections.emptyList();
        LogHelper.attachFeatureSetToLogs(featureSet);
        return featureSet;
    }

    public static String getJourneyEvent(JourneyRequest request)
            throws HttpResponseExceptionWithErrorBody {
        var parts = request.getJourneyUri().getPath().split("/");
        return parts[parts.length - 1];
    }

    public static String getJourneyParameter(JourneyRequest request, String key)
            throws HttpResponseExceptionWithErrorBody {
        return Stream.ofNullable(request.getJourneyUri().getQuery())
                .flatMap(queryString -> Arrays.stream(queryString.split("&")))
                .filter(queryParam -> queryParam.startsWith(String.format("%s=", key)))
                .findFirst()
                .map(queryParam -> queryParam.split("=", 2)[1])
                .filter(StringUtils::isNotBlank)
                .orElse(null);
    }

    public static String getScoreType(ProcessRequest request)
            throws HttpResponseExceptionWithErrorBody {
        return extractValueFromLambdaInput(request, "scoreType", ErrorResponse.MISSING_SCORE_TYPE);
    }

    public static Integer getScoreThreshold(ProcessRequest request)
            throws HttpResponseExceptionWithErrorBody {
        return extractValueFromLambdaInput(
                request, "scoreThreshold", ErrorResponse.MISSING_SCORE_THRESHOLD);
    }

    public static boolean getIsUserInitiated(ProcessRequest request)
            throws HttpResponseExceptionWithErrorBody {
        return Boolean.TRUE.equals(
                extractValueFromLambdaInput(
                        request,
                        IS_USER_INITIATED,
                        ErrorResponse.MISSING_IS_USER_INITIATED_PARAMETER));
    }

    public static boolean getDeleteOnlyGPG45VCs(ProcessRequest request)
            throws HttpResponseExceptionWithErrorBody {
        return Boolean.TRUE.equals(
                extractValueFromLambdaInput(
                        request,
                        DELETE_ONLY_GPG45_VCS,
                        ErrorResponse.MISSING_IS_RESET_DELETE_GPG45_ONLY_PARAMETER));
    }

    private static <T> T extractValueFromLambdaInput(
            ProcessRequest request, String key, ErrorResponse errorResponse)
            throws HttpResponseExceptionWithErrorBody {
        Map<String, Object> lambdaInput = request.getLambdaInput();
        if (lambdaInput == null) {
            LOGGER.error(LogHelper.buildLogMessage("Missing lambdaInput map"));
            throw new HttpResponseExceptionWithErrorBody(SC_BAD_REQUEST, errorResponse);
        }
        T value = (T) lambdaInput.get(key);
        if (value == null) {
            LOGGER.error(
                    LogHelper.buildLogMessage(String.format("Missing '%s' in lambdaInput", key)));
            throw new HttpResponseExceptionWithErrorBody(SC_BAD_REQUEST, errorResponse);
        }
        return value;
    }

    private static String getIpvSessionId(Map<String, String> headers)
            throws HttpResponseExceptionWithErrorBody {
        String ipvSessionId = RequestHelper.getHeaderByKey(headers, IPV_SESSION_ID_HEADER);
        String message = String.format("%s not present in header", IPV_SESSION_ID_HEADER);

        validateIpvSessionId(ipvSessionId, message, false);

        LogHelper.attachIpvSessionIdToLogs(ipvSessionId);
        return ipvSessionId;
    }

    private static String getIpvSessionId(JourneyRequest request, boolean allowBlank)
            throws HttpResponseExceptionWithErrorBody {
        String ipvSessionId = request.getIpvSessionId();

        validateIpvSessionId(ipvSessionId, "ipvSessionId not present in request", allowBlank);

        LogHelper.attachIpvSessionIdToLogs(ipvSessionId);
        return ipvSessionId;
    }

    private static void validateIpvSessionId(
            String ipvSessionId, String errorMessage, boolean allowBlank)
            throws HttpResponseExceptionWithErrorBody {
        if (isBlank(ipvSessionId)) {
            if (allowBlank) {
                LOGGER.warn(LogHelper.buildLogMessage(errorMessage));
            } else {
                LOGGER.error(LogHelper.buildLogMessage(errorMessage));
                throw new HttpResponseExceptionWithErrorBody(
                        SC_BAD_REQUEST, ErrorResponse.MISSING_IPV_SESSION_ID);
            }
        }
    }

    private static String getIpAddress(Map<String, String> headers)
            throws HttpResponseExceptionWithErrorBody {
        String ipAddress = RequestHelper.getHeaderByKey(headers, IP_ADDRESS_HEADER);
        validateIpAddress(ipAddress, String.format("%s not present in header", IP_ADDRESS_HEADER));
        return ipAddress;
    }

    private static void validateIpAddress(String ipAddress, String errorMessage)
            throws HttpResponseExceptionWithErrorBody {
        if (ipAddress == null) {
            LOGGER.error(LogHelper.buildErrorMessage(errorMessage, IP_ADDRESS_HEADER));
            throw new HttpResponseExceptionWithErrorBody(
                    SC_BAD_REQUEST, ErrorResponse.MISSING_IP_ADDRESS);
        }
    }

    private static void validateClientOAuthSessionId(
            String clientSessionId, StringMapMessage message) {
        if (clientSessionId == null) {
            LOGGER.warn(message);
        }
        LogHelper.attachClientSessionIdToLogs(clientSessionId);
    }
}
