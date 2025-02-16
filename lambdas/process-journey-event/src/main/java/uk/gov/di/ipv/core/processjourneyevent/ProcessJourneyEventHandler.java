package uk.gov.di.ipv.core.processjourneyevent;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.nimbusds.oauth2.sdk.OAuth2Error;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.StringMapMessage;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.tracing.Tracing;
import uk.gov.di.ipv.core.library.annotations.ExcludeFromGeneratedCoverageReport;
import uk.gov.di.ipv.core.library.auditing.AuditEvent;
import uk.gov.di.ipv.core.library.auditing.AuditEventTypes;
import uk.gov.di.ipv.core.library.auditing.AuditEventUser;
import uk.gov.di.ipv.core.library.auditing.extension.AuditExtensionMitigationType;
import uk.gov.di.ipv.core.library.auditing.extension.AuditExtensionSubjourneyType;
import uk.gov.di.ipv.core.library.config.ConfigurationVariable;
import uk.gov.di.ipv.core.library.domain.CoiSubjourneyType;
import uk.gov.di.ipv.core.library.domain.ErrorResponse;
import uk.gov.di.ipv.core.library.domain.IpvJourneyTypes;
import uk.gov.di.ipv.core.library.domain.JourneyRequest;
import uk.gov.di.ipv.core.library.exceptions.HttpResponseExceptionWithErrorBody;
import uk.gov.di.ipv.core.library.exceptions.SqsException;
import uk.gov.di.ipv.core.library.helpers.LogHelper;
import uk.gov.di.ipv.core.library.helpers.RequestHelper;
import uk.gov.di.ipv.core.library.helpers.StepFunctionHelpers;
import uk.gov.di.ipv.core.library.persistence.item.ClientOAuthSessionItem;
import uk.gov.di.ipv.core.library.persistence.item.IpvSessionItem;
import uk.gov.di.ipv.core.library.service.AuditService;
import uk.gov.di.ipv.core.library.service.ClientOAuthSessionDetailsService;
import uk.gov.di.ipv.core.library.service.ConfigService;
import uk.gov.di.ipv.core.library.service.IpvSessionService;
import uk.gov.di.ipv.core.processjourneyevent.exceptions.JourneyEngineException;
import uk.gov.di.ipv.core.processjourneyevent.statemachine.StateMachine;
import uk.gov.di.ipv.core.processjourneyevent.statemachine.StateMachineInitializer;
import uk.gov.di.ipv.core.processjourneyevent.statemachine.StateMachineInitializerMode;
import uk.gov.di.ipv.core.processjourneyevent.statemachine.exceptions.StateMachineNotFoundException;
import uk.gov.di.ipv.core.processjourneyevent.statemachine.exceptions.UnknownEventException;
import uk.gov.di.ipv.core.processjourneyevent.statemachine.exceptions.UnknownStateException;
import uk.gov.di.ipv.core.processjourneyevent.statemachine.states.BasicState;
import uk.gov.di.ipv.core.processjourneyevent.statemachine.states.JourneyChangeState;
import uk.gov.di.ipv.core.processjourneyevent.statemachine.states.State;
import uk.gov.di.ipv.core.processjourneyevent.statemachine.stepresponses.JourneyContext;
import uk.gov.di.ipv.core.processjourneyevent.statemachine.stepresponses.ProcessStepResponse;
import uk.gov.di.ipv.core.processjourneyevent.statemachine.stepresponses.StepResponse;

import java.io.IOException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.BACKEND_SESSION_TIMEOUT;
import static uk.gov.di.ipv.core.library.domain.CoiSubjourneyType.isCoiSubjourneyEvent;
import static uk.gov.di.ipv.core.library.domain.IpvJourneyTypes.SESSION_TIMEOUT;
import static uk.gov.di.ipv.core.library.helpers.LogHelper.LogField.LOG_JOURNEY_EVENT;
import static uk.gov.di.ipv.core.library.helpers.LogHelper.LogField.LOG_JOURNEY_TYPE;
import static uk.gov.di.ipv.core.library.helpers.LogHelper.LogField.LOG_MESSAGE_DESCRIPTION;
import static uk.gov.di.ipv.core.library.helpers.LogHelper.LogField.LOG_USER_STATE;

public class ProcessJourneyEventHandler
        implements RequestHandler<JourneyRequest, Map<String, Object>> {
    private static final Logger LOGGER = LogManager.getLogger();
    public static final String CURRENT_PAGE = "currentPage";
    private static final String CORE_SESSION_TIMEOUT_STATE = "CORE_SESSION_TIMEOUT";
    private static final String NEXT_EVENT = "next";
    private static final String END_SESSION_EVENT = "build-client-oauth-response";
    private static final StepResponse END_SESSION_RESPONSE =
            new ProcessStepResponse("build-client-oauth-response", null, null);
    private final IpvSessionService ipvSessionService;
    private final AuditService auditService;
    private final ConfigService configService;
    private final ClientOAuthSessionDetailsService clientOAuthSessionService;
    private final Map<IpvJourneyTypes, StateMachine> stateMachines;

    public ProcessJourneyEventHandler(
            AuditService auditService,
            IpvSessionService ipvSessionService,
            ConfigService configService,
            ClientOAuthSessionDetailsService clientOAuthSessionService,
            List<IpvJourneyTypes> journeyTypes,
            StateMachineInitializerMode stateMachineInitializerMode)
            throws IOException {
        this.ipvSessionService = ipvSessionService;
        this.auditService = auditService;
        this.configService = configService;
        this.clientOAuthSessionService = clientOAuthSessionService;
        this.stateMachines = loadStateMachines(journeyTypes, stateMachineInitializerMode);
    }

    @ExcludeFromGeneratedCoverageReport
    public ProcessJourneyEventHandler() throws IOException {
        this.configService = new ConfigService();
        this.auditService = new AuditService(AuditService.getSqsClient(), configService);
        this.ipvSessionService = new IpvSessionService(configService);
        this.clientOAuthSessionService = new ClientOAuthSessionDetailsService(configService);
        this.stateMachines =
                loadStateMachines(
                        List.of(IpvJourneyTypes.values()), StateMachineInitializerMode.STANDARD);
    }

    @Override
    @Tracing
    @Logging(clearState = true)
    public Map<String, Object> handleRequest(JourneyRequest journeyRequest, Context context) {
        LogHelper.attachComponentId(configService);

        try {
            // Extract variables
            String ipvSessionId = RequestHelper.getIpvSessionId(journeyRequest);
            String ipAddress = RequestHelper.getIpAddress(journeyRequest);
            String journeyEvent = RequestHelper.getJourneyEvent(journeyRequest);
            String currentPage = RequestHelper.getJourneyParameter(journeyRequest, CURRENT_PAGE);
            configService.setFeatureSet(RequestHelper.getFeatureSet(journeyRequest));

            // Handle route direct back to RP (used for recoverable timeouts)
            if (journeyEvent.equals(END_SESSION_EVENT)) {
                LOGGER.warn(LogHelper.buildLogMessage("Returning end session response directly"));
                return END_SESSION_RESPONSE.value();
            }

            // Get/ set session items/ config
            IpvSessionItem ipvSessionItem = ipvSessionService.getIpvSession(ipvSessionId);

            if (ipvSessionItem == null) {
                LOGGER.error(LogHelper.buildLogMessage("Failed to find ipv-session"));
                throw new HttpResponseExceptionWithErrorBody(
                        HttpStatus.SC_BAD_REQUEST, ErrorResponse.INVALID_SESSION_ID);
            }

            if (isCoiSubjourneyEvent(journeyEvent)) {
                CoiSubjourneyType coiJourneyType = CoiSubjourneyType.fromString(journeyEvent);

                ipvSessionItem.setCoiSubjourneyType(coiJourneyType);
            }

            ClientOAuthSessionItem clientOAuthSessionItem =
                    clientOAuthSessionService.getClientOAuthSession(
                            ipvSessionItem.getClientOAuthSessionId());

            // Attach variables to logs
            LogHelper.attachGovukSigninJourneyIdToLogs(
                    clientOAuthSessionItem.getGovukSigninJourneyId());

            var auditEventUser =
                    new AuditEventUser(
                            clientOAuthSessionItem.getUserId(),
                            ipvSessionId,
                            clientOAuthSessionItem.getGovukSigninJourneyId(),
                            ipAddress);

            StepResponse stepResponse =
                    executeJourneyEvent(journeyEvent, ipvSessionItem, auditEventUser, currentPage);

            if (stepResponse.getMitigationStart() != null) {
                sendMitigationStartAuditEvent(auditEventUser, stepResponse.getMitigationStart());
            }

            return stepResponse.value();
        } catch (HttpResponseExceptionWithErrorBody e) {
            return StepFunctionHelpers.generateErrorOutputMap(
                    e.getResponseCode(), e.getErrorResponse());
        } catch (JourneyEngineException e) {
            return StepFunctionHelpers.generateErrorOutputMap(
                    HttpStatus.SC_INTERNAL_SERVER_ERROR, ErrorResponse.FAILED_JOURNEY_ENGINE_STEP);
        } catch (SqsException e) {
            return StepFunctionHelpers.generateErrorOutputMap(
                    HttpStatus.SC_INTERNAL_SERVER_ERROR, ErrorResponse.FAILED_TO_SEND_AUDIT_EVENT);
        }
    }

    @Tracing
    private StepResponse executeJourneyEvent(
            String journeyEvent,
            IpvSessionItem ipvSessionItem,
            AuditEventUser auditEventUser,
            String currentPage)
            throws JourneyEngineException, SqsException {
        if (sessionIsNewlyExpired(ipvSessionItem)) {
            updateUserSessionForTimeout(
                    ipvSessionItem.getUserState(), ipvSessionItem, auditEventUser);
            journeyEvent = NEXT_EVENT;
        }

        try {
            var newState = executeStateTransition(ipvSessionItem, journeyEvent, currentPage);

            while (newState instanceof JourneyChangeState journeyChangeState) {
                LOGGER.info(
                        LogHelper.buildLogMessage("Transitioned to new journey type")
                                .with(
                                        LOG_JOURNEY_TYPE.getFieldName(),
                                        journeyChangeState.getJourneyType())
                                .with(
                                        LOG_USER_STATE.getFieldName(),
                                        journeyChangeState.getInitialState()));
                ipvSessionItem.setJourneyType(journeyChangeState.getJourneyType());
                ipvSessionItem.setUserState(journeyChangeState.getInitialState());
                sendSubJourneyStartAuditEvent(auditEventUser, journeyChangeState.getJourneyType());
                newState = executeStateTransition(ipvSessionItem, NEXT_EVENT, null);
            }

            var basicState = (BasicState) newState;

            updateUserState(
                    ipvSessionItem.getUserState(),
                    basicState.getName(),
                    journeyEvent,
                    ipvSessionItem);

            clearOauthSessionIfExists(ipvSessionItem);

            ipvSessionService.updateIpvSession(ipvSessionItem);

            return basicState.getResponse();
        } catch (UnknownStateException e) {
            LOGGER.error(
                    new StringMapMessage()
                            .with(LOG_MESSAGE_DESCRIPTION.getFieldName(), e.getMessage())
                            .with(LOG_USER_STATE.getFieldName(), ipvSessionItem.getUserState()));
            throw new JourneyEngineException(
                    "Invalid journey state encountered, failed to execute journey engine step.");
        } catch (UnknownEventException e) {
            LOGGER.error(
                    new StringMapMessage()
                            .with(LOG_MESSAGE_DESCRIPTION.getFieldName(), e.getMessage())
                            .with(LOG_JOURNEY_EVENT.getFieldName(), journeyEvent));
            throw new JourneyEngineException(
                    "Invalid journey event provided, failed to execute journey engine step.");
        } catch (StateMachineNotFoundException e) {
            LOGGER.error(
                    new StringMapMessage()
                            .with(LOG_MESSAGE_DESCRIPTION.getFieldName(), e.getMessage())
                            .with(LOG_JOURNEY_EVENT.getFieldName(), journeyEvent)
                            .with(
                                    LOG_JOURNEY_TYPE.getFieldName(),
                                    ipvSessionItem.getJourneyType()));
            throw new JourneyEngineException(
                    "State machine not found for journey type, failed to execute journey engine step");
        }
    }

    @Tracing
    private State executeStateTransition(
            IpvSessionItem ipvSessionItem, String journeyEvent, String currentPage)
            throws StateMachineNotFoundException, UnknownEventException, UnknownStateException {
        StateMachine stateMachine = stateMachines.get(ipvSessionItem.getJourneyType());
        if (stateMachine == null) {
            throw new StateMachineNotFoundException(
                    String.format(
                            "State machine not found for journey type: '%s'",
                            ipvSessionItem.getJourneyType()));
        }
        LOGGER.debug(
                LogHelper.buildLogMessage(
                        String.format(
                                "Found state machine for journey type: %s",
                                ipvSessionItem.getJourneyType().name())));

        return stateMachine.transition(
                ipvSessionItem.getUserState(),
                journeyEvent,
                new JourneyContext(configService),
                currentPage);
    }

    @Tracing
    private void updateUserState(
            String oldState, String newState, String journeyEvent, IpvSessionItem ipvSessionItem) {
        ipvSessionItem.setUserState(newState);
        var message =
                new StringMapMessage()
                        .with("journeyEngine", "State transition")
                        .with("event", journeyEvent)
                        .with("from", oldState)
                        .with("to", newState);
        LOGGER.info(message);
    }

    @Tracing
    private void clearOauthSessionIfExists(IpvSessionItem ipvSessionItem) {
        if (ipvSessionItem.getCriOAuthSessionId() != null) {
            ipvSessionItem.setCriOAuthSessionId(null);
        }
    }

    @Tracing
    private void updateUserSessionForTimeout(
            String oldState, IpvSessionItem ipvSessionItem, AuditEventUser auditEventUser)
            throws SqsException {
        ipvSessionItem.setErrorCode(OAuth2Error.ACCESS_DENIED.getCode());
        ipvSessionItem.setErrorDescription(OAuth2Error.ACCESS_DENIED.getDescription());
        ipvSessionItem.setJourneyType(SESSION_TIMEOUT);
        updateUserState(oldState, CORE_SESSION_TIMEOUT_STATE, "timeout", ipvSessionItem);
        sendSubJourneyStartAuditEvent(auditEventUser, SESSION_TIMEOUT);
    }

    @Tracing
    private boolean sessionIsNewlyExpired(IpvSessionItem ipvSessionItem) {
        return (!SESSION_TIMEOUT.equals(ipvSessionItem.getJourneyType()))
                && Instant.parse(ipvSessionItem.getCreationDateTime())
                        .isBefore(
                                Instant.now()
                                        .minusSeconds(
                                                Long.parseLong(
                                                        configService.getSsmParameter(
                                                                BACKEND_SESSION_TIMEOUT))));
    }

    @Tracing
    private Map<IpvJourneyTypes, StateMachine> loadStateMachines(
            List<IpvJourneyTypes> journeyTypes,
            StateMachineInitializerMode stateMachineInitializerMode)
            throws IOException {
        EnumMap<IpvJourneyTypes, StateMachine> stateMachinesMap =
                new EnumMap<>(IpvJourneyTypes.class);
        for (IpvJourneyTypes journeyType : journeyTypes) {
            stateMachinesMap.put(
                    journeyType,
                    new StateMachine(
                            new StateMachineInitializer(journeyType, stateMachineInitializerMode)));
        }
        return stateMachinesMap;
    }

    private void sendMitigationStartAuditEvent(AuditEventUser auditEventUser, String mitigationType)
            throws SqsException {

        auditService.sendAuditEvent(
                new AuditEvent(
                        AuditEventTypes.IPV_MITIGATION_START,
                        configService.getSsmParameter(ConfigurationVariable.COMPONENT_ID),
                        auditEventUser,
                        new AuditExtensionMitigationType(mitigationType)));
    }

    private void sendSubJourneyStartAuditEvent(
            AuditEventUser auditEventUser, IpvJourneyTypes journeyType) throws SqsException {
        auditService.sendAuditEvent(
                new AuditEvent(
                        AuditEventTypes.IPV_SUBJOURNEY_START,
                        configService.getSsmParameter(ConfigurationVariable.COMPONENT_ID),
                        auditEventUser,
                        new AuditExtensionSubjourneyType(journeyType)));
    }
}
