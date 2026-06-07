package de.metis.kernel.goal;

import java.util.*;
import java.util.function.IntFunction;
import java.util.logging.Logger;

/**
 * Phase 9.7-Followup (Sprint #2 Variante B, 08.06.2026).
 *
 * <p>Wertet periodisch jeden {@link LongHorizonGoal} gegen strukturierte
 * Postconditions in seiner Description aus und setzt {@code progress} +
 * {@code Status} entsprechend. Dadurch wird {@code goal_completion} im
 * Capability-Board ein echter, falsifizierbarer Schalter \u2014 ohne
 * Substring-Match-Heuristik im PLANNING-Scorer.
 *
 * <p><b>Postcondition-Syntax</b> (alles innerhalb des Goal.description):
 * <pre>
 * [postconditions]
 * beliefs_with_source_prefix:ethics: &gt;= 50
 * beliefs_with_source_prefix:wiki:Coburg &gt;= 100
 * child_goals_done &gt;= 3
 * [/postconditions]
 * </pre>
 *
 * <p>Unterst\u00fctzte linke Seite:
 * <ul>
 *   <li>{@code beliefs_with_source_prefix:&lt;prefix&gt;} \u2192 vom Caller geliefert via
 *       {@code beliefCountSupplier}</li>
 *   <li>{@code child_goals_done} \u2192 Anzahl DONE-Kinder direkt in Hierarchy</li>
 *   <li>{@code child_goals_total} \u2192 Anzahl aller Kinder</li>
 * </ul>
 *
 * <p>Unterst\u00fctzte Operatoren: {@code &gt;=}, {@code &gt;}, {@code &lt;=}, {@code &lt;}, {@code ==}.
 *
 * <p>Deterministisch, kein LLM. Wenn das Description-Format nicht parsbar
 * ist, bleibt das Goal unangetastet (kein progress-Reset).
 */
public class GoalCompletionEvaluator {

    private static final Logger LOG = Logger.getLogger(GoalCompletionEvaluator.class.getName());

    /** Beliefs-Count nach Source-Prefix \u2014 z.B. KnowledgeStore::countBeliefsBySourcePrefix. */
    @FunctionalInterface
    public interface BeliefCounter {
        int countBeliefsBySourcePrefix(String prefix);
    }

    private final GoalHierarchy hierarchy;
    private final BeliefCounter beliefCounter;

    public GoalCompletionEvaluator(GoalHierarchy hierarchy, BeliefCounter beliefCounter) {
        this.hierarchy = Objects.requireNonNull(hierarchy);
        this.beliefCounter = Objects.requireNonNull(beliefCounter);
    }

    /**
     * Wertet alle offenen Goals aus. Aktualisiert {@code progress} (bei <1.0)
     * oder setzt Status auf DONE (bei =1.0).
     *
     * @return Report mit Anzahl evaluierter, progress-aktualisierter und
     *         abgeschlossener Goals.
     */
    public synchronized Report evaluateAll() {
        int evaluated = 0;
        int progressed = 0;
        int completed = 0;
        List<String> notes = new ArrayList<>();

        for (var g : hierarchy.all()) {
            if (g.status() == LongHorizonGoal.Status.DONE
                    || g.status() == LongHorizonGoal.Status.ABANDONED) {
                continue;
            }
            List<Postcondition> conds = parse(g.rationale());
            if (conds.isEmpty()) continue;

            evaluated++;
            double newProgress = evaluateConditions(g, conds);
            // Conservative: progress only ever monotonically up.
            double targetProgress = Math.max(g.progress(), newProgress);

            if (targetProgress >= 0.9999) {
                hierarchy.upsert(g.withProgress(1.0)
                        .withStatus(LongHorizonGoal.Status.DONE));
                completed++;
                notes.add("DONE: " + truncate(g.title(), 60) + " (postconditions all met)");
                LOG.info("GoalCompletionEvaluator: DONE \"" + g.title()
                        + "\" (" + conds.size() + " postconditions met)");
            } else if (Math.abs(targetProgress - g.progress()) > 0.001) {
                hierarchy.upsert(g.withProgress(targetProgress));
                progressed++;
                notes.add("progress \u2192 " + String.format(java.util.Locale.ROOT, "%.2f", targetProgress)
                        + ": " + truncate(g.title(), 60));
            }
        }

        return new Report(evaluated, progressed, completed, notes);
    }

    // ── Parsing ─────────────────────────────────────────────────────

    static List<Postcondition> parse(String description) {
        if (description == null) return List.of();
        int start = description.indexOf("[postconditions]");
        int end = description.indexOf("[/postconditions]");
        if (start < 0 || end < 0 || end <= start) return List.of();

        String block = description.substring(start + "[postconditions]".length(), end);
        List<Postcondition> out = new ArrayList<>();
        for (String raw : block.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;

            // Find operator
            String[] ops = {">=", "<=", "==", ">", "<"};
            String foundOp = null;
            int opIdx = -1;
            for (String op : ops) {
                int idx = line.indexOf(op);
                if (idx >= 0) { foundOp = op; opIdx = idx; break; }
            }
            if (foundOp == null) continue;

            String lhs = line.substring(0, opIdx).strip();
            String rhs = line.substring(opIdx + foundOp.length()).strip();
            try {
                int threshold = Integer.parseInt(rhs);
                out.add(new Postcondition(lhs, foundOp, threshold));
            } catch (NumberFormatException nfe) {
                LOG.fine("Skipping unparseable postcondition: " + line);
            }
        }
        return out;
    }

    // ── Evaluation ──────────────────────────────────────────────────

    private double evaluateConditions(LongHorizonGoal goal, List<Postcondition> conds) {
        int met = 0;
        for (var c : conds) {
            int actual = currentValue(goal, c.lhs());
            if (compare(actual, c.op(), c.threshold())) {
                met++;
            }
        }
        return (double) met / conds.size();
    }

    private int currentValue(LongHorizonGoal goal, String lhs) {
        if (lhs.startsWith("beliefs_with_source_prefix:")) {
            String prefix = lhs.substring("beliefs_with_source_prefix:".length()).strip();
            return beliefCounter.countBeliefsBySourcePrefix(prefix);
        }
        if (lhs.equals("child_goals_done")) {
            int n = 0;
            for (var id : goal.childIds()) {
                var child = hierarchy.get(id);
                if (child.isPresent() && child.get().status() == LongHorizonGoal.Status.DONE) n++;
            }
            return n;
        }
        if (lhs.equals("child_goals_total")) {
            return goal.childIds() == null ? 0 : goal.childIds().size();
        }
        return 0;
    }

    private static boolean compare(int actual, String op, int threshold) {
        return switch (op) {
            case ">=" -> actual >= threshold;
            case ">"  -> actual >  threshold;
            case "<=" -> actual <= threshold;
            case "<"  -> actual <  threshold;
            case "==" -> actual == threshold;
            default   -> false;
        };
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "\u2026";
    }

    // ── Records ─────────────────────────────────────────────────────

    public record Postcondition(String lhs, String op, int threshold) {
        public String pretty() { return lhs + " " + op + " " + threshold; }
    }

    public record Report(int evaluated, int progressUpdated, int completed, List<String> notes) {
        public boolean anyChange() { return progressUpdated > 0 || completed > 0; }
    }
}
