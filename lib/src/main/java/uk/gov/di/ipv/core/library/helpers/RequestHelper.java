package uk.gov.di.ipv.core.library.helpers;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.nimbusds.oauth2.sdk.util.StringUtils;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.StringMapMessage;
import uk.gov.di.ipv.core.library.domain.ErrorResponse;
import uk.gov.di.ipv.core.library.domain.JourneyRequest;
import uk.gov.di.ipv.core.library.domain.ProcessRequest;
import uk.gov.di.ipv.core.library.exceptions.HttpResponseExceptionWithErrorBody;

import java.util.Map;
import java.util.Objects;

import static uk.gov.di.ipv.core.library.helpers.LogHelper.LogField.LOG_MESSAGE_DESCRIPTION;

public class RequestHelper {

    public static final String IPV_SESSION_ID_HEADER = "ipv-session-id";
    public static final String IP_ADDRESS_HEADER = "ip-address";
    public static final String FEATURE_SET_HEADER = "feature-set";
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
        return getIpvSessionId(event.getHeaders(), false);
    }

    public static String getIpvSessionId(JourneyRequest event)
            throws HttpResponseExceptionWithErrorBody {
        return getIpvSessionId(event, false);
    }

    public static String getIpvSessionIdAllowNull(JourneyRequest event)
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

    public static String getIpvSessionId(JourneyRequest request, boolean allowNull)
            throws HttpResponseExceptionWithErrorBody {
        String ipvSessionId = request.getIpvSessionId();

        validateIpvSessionId(ipvSessionId, "ipvSessionId not present in request", allowNull);

        LogHelper.attachIpvSessionIdToLogs(ipvSessionId);
        return ipvSessionId;
    }

    public static String getFeatureSet(JourneyRequest request) {
        String featureSet = request.getFeatureSet();
        LogHelper.attachFeatureSetToLogs(featureSet);
        return StringUtils.isBlank(featureSet) ? null : featureSet;
    }

    private static String getFeatureSet(Map<String, String> headers) {
        return RequestHelper.getHeaderByKey(headers, FEATURE_SET_HEADER);
    }

    public static String getFeatureSet(APIGatewayProxyRequestEvent event) {
        String featureSet = getFeatureSet(event.getHeaders());
        LogHelper.attachFeatureSetToLogs(featureSet);
        return featureSet;
    }

    public static String getJourney(JourneyRequest request) {
        return request.getJourney();
    }

    public static String getScoreType(ProcessRequest request) {
        String scoreType = request.getScoreType();
        if (scoreType == null) {
            LOGGER.error("Missing score type in request");
            throw new HttpResponseExceptionWithErrorBody(
                    HttpStatus.SC_BAD_REQUEST, ErrorResponse.MISSING_SCORE_TYPE);
        }
        return scoreType;
    }

    public static Integer getScoreThreshold(ProcessRequest request) {
        String scoreThreshold = request.getScoreType();
        if (scoreThreshold == null) {
            LOGGER.error("Missing score threshold in request");
            throw new HttpResponseExceptionWithErrorBody(
                    HttpStatus.SC_BAD_REQUEST, ErrorResponse.MISSING_SCORE_THRESHOLD);
        }
        return scoreThreshold;
    }

    private static String getIpvSessionId(Map<String, String> headers, boolean allowNull)
            throws HttpResponseExceptionWithErrorBody {
        String ipvSessionId = RequestHelper.getHeaderByKey(headers, IPV_SESSION_ID_HEADER);
        String message = String.format("%s not present in header", IPV_SESSION_ID_HEADER);

        validateIpvSessionId(ipvSessionId, message, allowNull);

        LogHelper.attachIpvSessionIdToLogs(ipvSessionId);
        return ipvSessionId;
    }

    private static void validateIpvSessionId(
            String ipvSessionId, String errorMessage, boolean allowNull)
            throws HttpResponseExceptionWithErrorBody {
        if (ipvSessionId == null) {
            if (allowNull) {
                LOGGER.warn(errorMessage);
            } else {
                LOGGER.error(errorMessage);
                throw new HttpResponseExceptionWithErrorBody(
                        HttpStatus.SC_BAD_REQUEST, ErrorResponse.MISSING_IPV_SESSION_ID);
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
            LOGGER.error(errorMessage, IP_ADDRESS_HEADER);
            throw new HttpResponseExceptionWithErrorBody(
                    HttpStatus.SC_BAD_REQUEST, ErrorResponse.MISSING_IP_ADDRESS);
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
