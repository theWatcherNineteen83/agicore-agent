package de.metis.modules;

import de.metis.kernel.goal.Goal;
import de.metis.kernel.meta.MetaCognition;
import de.metis.kernel.world.WorldModel;
import de.metis.kernel.metrics.FitnessSignal;

import java.util.*;
import java.util.logging.Logger;

/**
 * Prediction-error-driven goal generator for intrinsic motivation.
 * <p>
 * Replaces random idle goals with curiosity-driven exploration:
 * <ul>
 *   <li>High Surprise → explore the surprising domain</li>
 *   <li>Low Surprise → explore least-visited belief areas</li>
 *   <li>Normal → balanced exploration</li>
 * </ul>
 */
public class CuriosityEngine {

    private static final Logger LOG = Logger.getLogger(CuriosityEngine.class.getName());
    private static final double HIGH_SURPRISE = 1.5;
    private static final double LOW_SURPRISE = 0.5;

    private final MetaCognition metacognition;
    private final WorldModel worldModel;
    private final FitnessSignal fitnessSignal;

    private final Map<String, Integer> domainVisits = new HashMap<>();
    private final List<String> explorationDomains = List.of(
            "shell", "http", "filesystem", "webscrape", "linux-explore",
            "api-explore", "hw-profile", "deepnetts", "tornadovm"
    );
    private final Random random = new Random();

    public CuriosityEngine(MetaCognition metacognition, WorldModel worldModel,
                           FitnessSignal fitnessSignal) {
        this.metacognition = metacognition;
        this.worldModel = worldModel;
        this.fitnessSignal = fitnessSignal;
    }

    /**
     * Generate the next exploration goal based on curiosity signals.
     */
    public Goal generateExplorationGoal() {
        if (!fitnessSignal.isCalibrated()) {
            // Pre-calibration: balanced exploration across all domains
            return balancedExploration();
        }

        double surprise = metacognition.errorStdDev();
        double target = fitnessSignal.targetSurprise();

        if (surprise > target * HIGH_SURPRISE) {
            return exploreSurprisingDomain();
        } else if (surprise < target * LOW_SURPRISE) {
            return exploreLeastVisitedDomain();
        } else {
            return balancedExploration();
        }
    }

    /** Explore the domain with highest surprise. */
    private Goal exploreSurprisingDomain() {
        // Find which domains have high prediction error
        var beliefs = worldModel.all();
        Map<String, Double> domainErrors = new HashMap<>();

        for (var belief : beliefs) {
            for (String domain : explorationDomains) {
                if (belief.statement().toLowerCase().contains(domain)) {
                    domainErrors.merge(domain, 1.0 - belief.confidence(), Double::sum);
                }
            }
        }

        // Pick domain with highest cumulative error
        String targetDomain = domainErrors.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(explorationDomains.get(random.nextInt(explorationDomains.size())));

        domainVisits.merge(targetDomain, 1, Integer::sum);

        String description = String.format(
                "Curiosity: high-surprise exploration of %s (error=%.2f)",
                targetDomain, domainErrors.getOrDefault(targetDomain, 0.0));
        return new Goal(description, targetDomain, 45, 0.55, 1);
    }

    /** Explore domains we know least about. */
    private Goal exploreLeastVisitedDomain() {
        String targetDomain = explorationDomains.stream()
                .min(Comparator.comparingInt(d -> domainVisits.getOrDefault(d, 0)))
                .orElse("shell");

        domainVisits.merge(targetDomain, 1, Integer::sum);

        // Find least-confident belief in this domain
        var beliefs = worldModel.all();
        double lowConfidence = beliefs.stream()
                .filter(b -> b.statement().toLowerCase().contains(targetDomain))
                .mapToDouble(b -> b.confidence())
                .min().orElse(1.0);

        String description = String.format(
                "Curiosity: low-surprise exploration of %s (minConfidence=%.2f, visits=%d)",
                targetDomain, lowConfidence, domainVisits.getOrDefault(targetDomain, 0));
        return new Goal(description, targetDomain, 42, 0.50, 1);
    }

    /** Balanced exploration with slight preference for under-visited domains. */
    private Goal balancedExploration() {
        // Weight domains by inverse visit count (explore unfamiliar territory)
        double totalWeight = explorationDomains.stream()
                .mapToDouble(d -> 1.0 / (domainVisits.getOrDefault(d, 0) + 1))
                .sum();

        double rand = random.nextDouble() * totalWeight;
        double cumulative = 0;
        String chosen = explorationDomains.get(0);

        for (String domain : explorationDomains) {
            cumulative += 1.0 / (domainVisits.getOrDefault(domain, 0) + 1);
            if (rand <= cumulative) {
                chosen = domain;
                break;
            }
        }

        domainVisits.merge(chosen, 1, Integer::sum);

        String description = String.format(
                "Curiosity: balanced exploration of %s (visits=%d)",
                chosen, domainVisits.getOrDefault(chosen, 0));
        return new Goal(description, chosen, 40, 0.45, 1);
    }
}
