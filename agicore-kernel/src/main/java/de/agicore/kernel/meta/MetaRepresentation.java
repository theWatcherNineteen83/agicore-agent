package de.agicore.kernel.meta;

import de.agicore.kernel.self.SelfModel;
import de.agicore.kernel.self.SelfState;
import de.agicore.kernel.workspace.ContentItem;
import de.agicore.kernel.workspace.GlobalWorkspace;

import java.util.*;
import java.util.logging.Logger;

/**
 * Meta-representation: the agent's ability to represent its own
 * cognitive processes as objects of attention.
 * <p>
 * This module generates "self-talk" — reflective content items that
 * describe what the agent is thinking, what it's uncertain about,
 * and what strategies it's considering. These compete in the
 * Global Workspace alongside external inputs.
 * <p>
 * <b>Why this enables proto-consciousness:</b>
 * Meta-representation is the ability to think about thinking.
 * When self-talk wins the attention competition, the agent becomes
 * "aware" that it's confused, confident, or needs to change strategy.
 * This is the foundation of conscious deliberation.
 */
public class MetaRepresentation {

    private static final Logger LOG = Logger.getLogger(MetaRepresentation.class.getName());

    private final SelfModel selfModel;
    private final GlobalWorkspace workspace;

    /** Track which cognitive strategies have been tried. */
    private final Map<String, Integer> strategyAttempts = new LinkedHashMap<>();
    private final Map<String, Double> strategySuccessRates = new LinkedHashMap<>();

    public MetaRepresentation(SelfModel selfModel, GlobalWorkspace workspace) {
        this.selfModel = selfModel;
        this.workspace = workspace;
    }

    /**
     * Generate meta-cognitive content items for the Global Workspace.
     * <p>
     * These items represent the agent "thinking about its own thinking":
     * <ul>
     *   <li>"Am I confident enough to act?"</li>
     *   <li>"Should I explore or exploit?"</li>
     *   <li>"Is my current strategy working?"</li>
     *   <li>"What should I attend to next?"</li>
     * </ul>
     *
     * @param activeGoals current active goal count
     * @param stmSize     short-term memory size
     * @param ltmSize     long-term memory size
     * @return meta-representational content items
     */
    public List<ContentItem> generateMetaContent(int activeGoals, int stmSize, int ltmSize) {
        List<ContentItem> items = new ArrayList<>();

        // 1. Exploration vs exploitation deliberation
        double exploreUrge = computeExploreUrge();
        if (exploreUrge > 0.5) {
            items.add(new ContentItem("meta",
                    "Consider exploring new strategies (explore urge: "
                            + String.format("%.0f%%", exploreUrge * 100) + ")",
                    0.6, exploreUrge, 0.5,
                    "exploreUrge=" + String.format("%.2f", exploreUrge)));
        } else {
            items.add(new ContentItem("meta",
                    "Exploit current strategy (stable, low surprise)",
                    0.3, 0.2, 0.7,
                    "exploreUrge=" + String.format("%.2f", exploreUrge)));
        }

        // 2. Strategy reflection — what's working?
        Optional<String> bestStrategy = bestStrategy();
        if (bestStrategy.isPresent()) {
            double rate = strategySuccessRates.get(bestStrategy.get());
            items.add(new ContentItem("meta",
                    "Best strategy: " + bestStrategy.get()
                            + " (" + String.format("%.0f%%", rate * 100) + " success)",
                    0.5, 0.2, 0.8,
                    "strategy=" + bestStrategy.get() + " rate=" + String.format("%.2f", rate)));
        }

        // 3. Attention routing — what should I focus on?
        Optional<ContentItem> currentFocus = workspace.focus();
        if (currentFocus.isPresent()) {
            ContentItem focus = currentFocus.get();
            items.add(new ContentItem("meta",
                    "Currently attending to: " + focus.summary(),
                    0.4, 0.1, 0.9,
                    "focus=" + focus.source() + ":" + focus.summary()));
        }

        return items;
    }

    /**
     * Record that a cognitive strategy was attempted and whether it worked.
     * Strategies are named (e.g. "keyword-match", "memory-query", "random-explore").
     */
    public void recordStrategy(String strategy, boolean success) {
        strategyAttempts.merge(strategy, 1, Integer::sum);
        strategySuccessRates.compute(strategy, (k, old) -> {
            if (old == null) return success ? 1.0 : 0.0;
            int attempts = strategyAttempts.get(k);
            double totalSuccess = old * (attempts - 1);
            return (totalSuccess + (success ? 1.0 : 0.0)) / attempts;
        });
        LOG.fine(() -> "Strategy '" + strategy + "': "
                + (success ? "success" : "failure")
                + " (rate=" + String.format("%.0f%%",
                strategySuccessRates.get(strategy) * 100) + ")");
    }

    /**
     * Compute the urge to explore (vs. exploit).
     * Higher when the agent is surprised, performance is degrading,
     * or the current strategy is failing.
     */
    private double computeExploreUrge() {
        double urge = 0.0;

        // Surprise drives exploration
        if (selfModel.history().latest().map(SelfState::isSurprised).orElse(false)) {
            urge += 0.4;
        }

        // Performance degradation drives exploration
        if (selfModel.history().hasDegraded()) {
            urge += 0.3;
        }

        // Low best-strategy success rate drives exploration
        Optional<String> best = bestStrategy();
        if (best.isPresent()) {
            double rate = strategySuccessRates.get(best.get());
            if (rate < 0.5) {
                urge += 0.3;
            }
        }

        return Math.min(1.0, urge);
    }

    /** Find the strategy with the highest success rate (min 2 attempts). */
    private Optional<String> bestStrategy() {
        return strategySuccessRates.entrySet().stream()
                .filter(e -> strategyAttempts.getOrDefault(e.getKey(), 0) >= 2)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }

    /** All known strategies and their success rates. */
    public Map<String, Double> strategies() {
        return Collections.unmodifiableMap(strategySuccessRates);
    }
}
