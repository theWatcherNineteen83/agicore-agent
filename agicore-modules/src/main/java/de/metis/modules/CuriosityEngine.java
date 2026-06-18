package de.metis.modules;

import de.metis.kernel.goal.Goal;
import de.metis.kernel.meta.MetaCognition;
import de.metis.kernel.world.HypothesisGenerator;
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
    private final HypothesisGenerator hypothesisGenerator;

    private final Map<String, Integer> domainVisits = new HashMap<>();
    private final List<String> explorationDomains = List.of(
            "shell", "http", "filesystem", "webscrape", "linux-explore",
            "api-explore", "hw-profile", "deepnetts", "tornadovm",
            "wikipedia-learn"
    );
    private final Random random = new Random();

    public CuriosityEngine(MetaCognition metacognition, WorldModel worldModel,
                           FitnessSignal fitnessSignal,
                           HypothesisGenerator hypothesisGenerator) {
        this.metacognition = metacognition;
        this.worldModel = worldModel;
        this.fitnessSignal = fitnessSignal;
        this.hypothesisGenerator = hypothesisGenerator;
    }

    public CuriosityEngine(MetaCognition metacognition, WorldModel worldModel,
                           FitnessSignal fitnessSignal) {
        this(metacognition, worldModel, fitnessSignal, null);
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
            // Phase 10: auch kausale Hypothese aus dem Surprise-Event ableiten
            generateCausalHypothesis();
            return exploreSurprisingDomain();
        } else if (surprise < target * LOW_SURPRISE) {
            return exploreLeastVisitedDomain();
        } else {
            return balancedExploration();
        }
    }

    /**
     * Phase 10: Bei hohem Surprise eine kausale Hypothese ableiten.
     * Ruft HypothesisGenerator.propose() mit der aktuellen Metacognition auf.
     */
    private void generateCausalHypothesis() {
        if (hypothesisGenerator == null) return;
        double surprise = metacognition.errorStdDev();
        // Finde die Belief-Domäne mit der höchsten Unsicherheit
        var beliefs = worldModel.all();
        String worstDomain = "";
        double worstConfidence = 1.0;
        for (var b : beliefs) {
            String stmt = b.statement();
            for (String domain : explorationDomains) {
                if (stmt.toLowerCase().contains(domain) && b.confidence() < worstConfidence) {
                    worstConfidence = b.confidence();
                    worstDomain = domain;
                }
            }
        }
        if (worstDomain.isEmpty() && !explorationDomains.isEmpty()) {
            worstDomain = explorationDomains.get(0);
        }
        String cause = "high_surprise:" + worstDomain;
        String condition = "surprise=" + String.format(java.util.Locale.ROOT, "%.2f", surprise);
        String effect = "reduced_surprise";
        String rationale = "CuriosityEngine: Surprise " + String.format(java.util.Locale.ROOT, "%.2f", surprise)
                + " > threshold in domain '" + worstDomain
                + "' — Erkundung sollte Surprise senken";
        hypothesisGenerator.propose(cause, condition, effect, rationale);
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

    /**
     * Get interesting exploration domains for the Wikipedia knowledge service.
     * Returns domains/topics the engine is currently curious about.
     */
    public List<String> getExplorationDomains() {
        return domainVisits.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(3)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());
    }
}
