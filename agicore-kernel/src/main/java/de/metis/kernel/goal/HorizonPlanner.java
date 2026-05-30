package de.metis.kernel.goal;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Phase 9.3 — Deterministischer Top-Down-Decomposer.
 *
 * <p>Verfährt nach festem Schema:
 * <pre>
 *   STRATEGIC (Woche)   →  3 TACTICAL (Tag)        →  3 OPERATIONAL (Stunden)  →  Tick-Goals
 * </pre>
 *
 * <p>Bewusst zunächst <em>deterministisch</em>, damit das Verhalten testbar
 * bleibt. Ein optionaler {@link DecomposeFunction}-Hook macht einen LLM-Drop-in
 * möglich, ohne den Kernel von Ollama abhängig zu machen.
 *
 * <p>Diese Klasse erzeugt nur die Long-Horizon-Records und verknüpft Parent/
 * Children im {@link GoalHierarchy}. Sie schreibt <em>keine</em>
 * Kanban-Board-Einträge selbst — das macht der Aufrufer im AgentMain, wenn
 * ein OPERATIONAL-Goal "fällig" wird.
 */
public class HorizonPlanner {

    private static final Logger LOG = Logger.getLogger(HorizonPlanner.class.getName());

    /** Optional LLM-driven decomposition (Phase 9.3b). */
    public interface DecomposeFunction {
        List<String> proposeChildTitles(LongHorizonGoal parent, GoalHorizon childHorizon, int wantedCount);
    }

    private final GoalHierarchy hierarchy;
    private DecomposeFunction decomposer;
    private int defaultFanout = 3;

    public HorizonPlanner(GoalHierarchy hierarchy) {
        this.hierarchy = hierarchy;
    }

    public void setDecomposer(DecomposeFunction f) { this.decomposer = f; }
    public void setDefaultFanout(int n) { this.defaultFanout = Math.max(1, Math.min(8, n)); }

    /** Convenience: create a strategic goal with a typical 7-day deadline. */
    public LongHorizonGoal proposeStrategic(String title, String rationale, int priority, List<String> tags) {
        LongHorizonGoal g = new LongHorizonGoal(
                null, title, rationale, GoalHorizon.STRATEGIC,
                LongHorizonGoal.Status.PROPOSED,
                null, List.of(), Instant.now(),
                Instant.now().plus(Duration.ofDays(7)),
                null, null, 0.0, priority, "metis", tags
        );
        return hierarchy.upsert(g);
    }

    /**
     * Decompose a parent goal one level down. Idempotent: if the parent
     * already has children at the target horizon, nothing happens.
     *
     * @return new child goals (empty if parent already decomposed or horizon undecomposable)
     */
    public synchronized List<LongHorizonGoal> decompose(LongHorizonGoal parent) {
        if (parent == null || !parent.horizon().canBeDecomposed()) return List.of();
        GoalHorizon childHorizon = parent.horizon().nextDown();
        if (childHorizon == null) return List.of();

        // already decomposed?
        if (!hierarchy.children(parent.id()).isEmpty()) {
            return List.of();
        }

        List<String> titles = decomposer != null
                ? safeTitles(decomposer.proposeChildTitles(parent, childHorizon, defaultFanout), parent.title(), defaultFanout)
                : deterministicTitles(parent.title(), childHorizon, defaultFanout);

        Instant childDeadline = parent.deadline() != null
                ? parent.deadline()
                : Instant.now().plus(durationFor(childHorizon));

        List<LongHorizonGoal> created = new ArrayList<>();
        LongHorizonGoal current = parent;
        for (String t : titles) {
            LongHorizonGoal child = new LongHorizonGoal(
                    null, t,
                    "Zerlegt aus: " + parent.title(),
                    childHorizon,
                    LongHorizonGoal.Status.PROPOSED,
                    parent.id(),
                    List.of(),
                    Instant.now(),
                    childDeadline,
                    null, null, 0.0,
                    Math.max(1, parent.priority() - 5),
                    parent.owner(),
                    parent.tags()
            );
            child = hierarchy.upsert(child);
            current = current.withChild(child.id());
            created.add(child);
        }
        hierarchy.upsert(current);
        LOG.info("HorizonPlanner: decomposed '" + parent.title()
                + "' into " + created.size() + " " + childHorizon + " goals");
        return created;
    }

    /** Suggest the next runnable goals at OPERATIONAL horizon for tick translation. */
    public synchronized List<LongHorizonGoal> nextActionable(int limit) {
        return hierarchy.openByHorizon(GoalHorizon.OPERATIONAL).stream()
                .filter(g -> hierarchy.isRunnable(g.id()))
                .limit(Math.max(1, limit))
                .toList();
    }

    // ── Deterministic fallback ──

    private List<String> deterministicTitles(String parentTitle, GoalHorizon h, int n) {
        List<String> out = new ArrayList<>(n);
        String prefix = switch (h) {
            case TACTICAL    -> "Tag-Schritt";
            case OPERATIONAL -> "Block";
            case TICK        -> "Tick";
            default          -> "Schritt";
        };
        for (int i = 1; i <= n; i++) {
            out.add(prefix + " " + i + ": " + parentTitle);
        }
        return out;
    }

    private List<String> safeTitles(List<String> proposed, String fallbackTitle, int wanted) {
        if (proposed == null || proposed.isEmpty()) {
            return deterministicTitles(fallbackTitle, GoalHorizon.OPERATIONAL, wanted);
        }
        return proposed.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(t -> !t.isEmpty())
                .limit(wanted)
                .toList();
    }

    private Duration durationFor(GoalHorizon h) {
        return switch (h) {
            case TACTICAL    -> Duration.ofDays(1);
            case OPERATIONAL -> Duration.ofHours(4);
            case TICK        -> Duration.ofMinutes(5);
            default          -> Duration.ofDays(7);
        };
    }
}
