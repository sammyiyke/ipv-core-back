package uk.gov.di.ipv.core.library.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.gov.di.ipv.core.library.domain.ContraIndicators;
import uk.gov.di.ipv.core.library.domain.JourneyResponse;
import uk.gov.di.ipv.core.library.domain.MitigationRoute;
import uk.gov.di.ipv.core.library.domain.cimitvc.ContraIndicator;
import uk.gov.di.ipv.core.library.exceptions.ConfigException;

import java.util.List;
import java.util.Optional;

import static uk.gov.di.ipv.core.library.config.ConfigurationVariable.CI_SCORING_THRESHOLD;

public class CiMitUtilityService {
    private static final Logger LOGGER = LogManager.getLogger();
    private final ConfigService configService;

    public CiMitUtilityService(ConfigService configService) {
        this.configService = configService;
    }

    public boolean isBreachingCiThreshold(ContraIndicators contraIndicators) {
        return contraIndicators.getContraIndicatorScore(configService.getContraIndicatorConfigMap())
                > Integer.parseInt(configService.getSsmParameter(CI_SCORING_THRESHOLD));
    }

    public boolean isBreachingCiThresholdIfMitigated(ContraIndicator ci, ContraIndicators cis) {
        var scoreOnceMitigated =
                cis.getContraIndicatorScore(configService.getContraIndicatorConfigMap())
                        + configService
                                .getContraIndicatorConfigMap()
                                .get(ci.getCode())
                                .getCheckedScore();
        return scoreOnceMitigated
                > Integer.parseInt(configService.getSsmParameter(CI_SCORING_THRESHOLD));
    }

    public Optional<JourneyResponse> getCiMitigationJourneyStep(ContraIndicators contraIndicators)
            throws ConfigException {
        // Try to mitigate an unmitigated ci to resolve the threshold breach
        var cimitConfig = configService.getCimitConfig();
        for (var ci : contraIndicators.getContraIndicatorsMap().values()) {
            if (isCiMitigatable(ci) && !isBreachingCiThresholdIfMitigated(ci, contraIndicators)) {
                MitigationRoute mitigationRoute =
                        getMitigationRoute(cimitConfig.get(ci.getCode()), ci.getDocument());
                return mitigationRoute == null
                        ? Optional.empty()
                        : Optional.of(new JourneyResponse(mitigationRoute.getEvent()));
            }
        }
        return Optional.empty();
    }

    private MitigationRoute getMitigationRoute(
            List<MitigationRoute> mitigationRoute, String document) {
        String documentType = document != null ? document.split("/")[0] : null;
        return mitigationRoute.stream()
                .filter(mr -> (mr.getDocument() == null || mr.getDocument().equals(documentType)))
                .findFirst()
                .orElseGet(
                        () -> {
                            LOGGER.info("No mitigation journey route event found.");
                            return null;
                        });
    }

    private boolean isCiMitigatable(ContraIndicator ci) throws ConfigException {
        var cimitConfig = configService.getCimitConfig();
        return cimitConfig.containsKey(ci.getCode()) && !ci.isMitigated();
    }
}
