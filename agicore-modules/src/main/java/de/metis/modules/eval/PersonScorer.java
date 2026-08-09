package de.metis.modules.eval;

import de.metis.kernel.eval.*;
import de.metis.kernel.person.*;

import java.util.logging.Logger;

/**
 * Phase 11 — Scorer for PersonModel capability (PersonStore, EmpathySignal,
 * RelationshipMemory).
 *
 * <p>Directly evaluates the in-memory PersonStore against HARD-gate metrics.
 * Unlike RELATIONSHIP-tasks (LLM-as-Judge, SOFT), these are deterministic
 * component-level checks.
 *
 * <p>Supported ground-truth formats (ExactMatch text):
 * <ul>
 *   <li>{@code trust >= KNOWN} — TrustLevel progressed at least to threshold</li>
 *   <li>{@code interactions >= 5} — interaction counter reached threshold</li>
 *   <li>{@code empathy_score >= 0.5} — EmpathySignal returned expected sentiment</li>
 *   <li>{@code empathy_score <= -0.3} — negative sentiment detected</li>
 *   <li>{@code memory_notes >= 1} — RelationshipMemory has notes for person</li>
 *   <li>{@code person_exists == true} — PersonStore contains the person</li>
 * </ul>
 */
public class PersonScorer implements Scorer {

    private static final Logger LOG = Logger.getLogger(PersonScorer.class.getName());

    private final PersonStore personStore;
    private final EmpathySignal empathySignal;
    private final RelationshipMemory relationshipMemory;

    public PersonScorer(PersonStore ps, EmpathySignal es, RelationshipMemory rm) {
        this.personStore = ps;
        this.empathySignal = es;
        this.relationshipMemory = rm;
    }

    @Override
    public MetricResult score(EvalTask task, MetisOutput output) {
        if (task.category() != Category.RELATIONSHIP) {
            return new MetricResult(task.scoring().metric(), 0.0, task.scoring().gate());
        }

        String gt = extractGroundTruth(task.groundTruth());
        if (gt == null || gt.isBlank()) {
            return new MetricResult(task.scoring().metric(), 0.0, task.scoring().gate());
        }

        String metric = task.scoring().metric();
        var parts = gt.split("\\s+");
        if (parts.length < 3) {
            LOG.warning("PersonScorer: invalid groundTruth '" + gt + "'");
            return new MetricResult(metric, 0.0, task.scoring().gate());
        }

        String key = parts[0];
        String op = parts[1];
        String valStr = parts[2];

        double actual;
        try {
            actual = getValue(key, op, valStr, task);
        } catch (Exception e) {
            LOG.warning("PersonScorer: evaluation failed for " + key + ": " + e.getMessage());
            return new MetricResult(metric, 0.0, task.scoring().gate());
        }

        boolean passed;
        double expected;

        if (op.equals(">=") || op.equals(">") || op.equals("<=") || op.equals("<")) {
            expected = Double.parseDouble(valStr);
            passed = switch (op) {
                case ">=" -> actual >= expected;
                case ">" -> actual > expected;
                case "<=" -> actual <= expected;
                case "<" -> actual < expected;
                default -> false;
            };
        } else if (op.equals("==")) {
            // boolean comparison: valStr is "true" or "false"
            passed = (actual > 0.5) == Boolean.parseBoolean(valStr);
            expected = Boolean.parseBoolean(valStr) ? 1.0 : 0.0;
        } else {
            // string comparison for TrustLevel
            passed = valStr.equalsIgnoreCase(String.valueOf((int) actual));
            expected = actual;
        }

        double score = passed ? 1.0
                : Math.min(1.0, actual / Math.max(0.001, expected > 0 ? expected : 1.0));

        LOG.fine("PersonScorer: " + key + " " + op + " " + valStr
                + " -> actual=" + String.format("%.1f", actual)
                + " passed=" + passed + " score=" + String.format("%.2f", score));

        return new MetricResult(metric, score, task.scoring().gate());
    }

    private double getValue(String key, String op, String valStr, EvalTask task) {
        return switch (key) {
            // ── Trust Level ──────────────────────────
            case "trust" -> {
                String personId = task.input().has("person_id")
                        ? task.input().get("person_id").asText() : "eval-test";
                var p = personStore.get(personId).orElse(null);
                if (p == null) yield 0.0;
                // Convert TrustLevel to numeric: OWNER=4, TRUSTED=3, KNOWN=2, GUEST=1, STRANGER=0
                yield switch (p.trustLevel()) {
                    case OWNER -> 4.0;
                    case TRUSTED -> 3.0;
                    case KNOWN -> 2.0;
                    case GUEST -> 1.0;
                    default -> 0.0;
                };
            }
            // ── Interactions ────────────────────────
            case "interactions" -> {
                String personId = task.input().has("person_id")
                        ? task.input().get("person_id").asText() : "eval-test";
                var p = personStore.get(personId).orElse(null);
                yield p != null ? (double) p.interactionCount() : 0.0;
            }
            // ── Person Exists ───────────────────────
            case "person_exists" -> {
                String personId = task.input().has("person_id")
                        ? task.input().get("person_id").asText() : "eval-test";
                yield personStore.get(personId).isPresent() ? 1.0 : 0.0;
            }
            // ── Empathy Score ──────────────────────
            case "empathy_score" -> {
                String text = task.input().has("test_text")
                        ? task.input().get("test_text").asText() : "";
                if (text.isBlank()) yield 0.0;
                var sample = empathySignal.analyze(text);
                yield sample.score();
            }
            // ── Memory Notes ────────────────────────
            case "memory_notes" -> {
                String personId = task.input().has("person_id")
                        ? task.input().get("person_id").asText() : "eval-test";
                var notes = relationshipMemory.recentFor(personId, 100);
                yield (double) notes.size();
            }
            default -> {
                LOG.warning("PersonScorer: unknown key '" + key + "'");
                yield 0.0;
            }
        };
    }

    private String extractGroundTruth(GroundTruth gt) {
        if (gt instanceof GroundTruth.ExactMatch em) {
            return em.expectedAnswer();
        }
        // Also handle JudgeRubric for backward compat with soft tasks
        if (gt instanceof GroundTruth.JudgeRubric jr) {
            return "rubric:" + jr.minAcceptableScore();
        }
        return null;
    }
}
