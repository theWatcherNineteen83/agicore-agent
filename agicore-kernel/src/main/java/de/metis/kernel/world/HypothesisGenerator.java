package de.metis.kernel.world;

import java.util.*;
import java.util.logging.Logger;

/**
 * Phase 10.2 — leitet aus Surprise-Signalen Hypothesen ab.
 *
 * <p>Eingabe: ein "Surprise"-Trigger (ein Pair von Cause+Effect, das im
 * Belief-System hohe Surprise hatte). Ausgabe: eine {@link CausalHypothesis}
 * in {@link CausalHypothesis.Status#PROPOSED}, sofort persistierbar.
 *
 * <p>Die Generierung ist deterministisch und billig (keine LLM-Calls,
 * keine VRAM-Belastung). Ein LLM-Hook kann später analog zum
 * LlmDreamSummarizer ergänzt werden.
 *
 * <p>Heuristik:
 * <ul>
 *   <li>Direction: wenn cause Schlüsselwort "more/erhöhen/höher" → UP,
 *       "less/senken/weniger" → DOWN, sonst UP</li>
 *   <li>Magnitude: 0.6 als Default (mittlere Erwartung)</li>
 *   <li>plannedAction: kurze deutsche Selbst-Anweisung</li>
 * </ul>
 */
public class HypothesisGenerator {

    private static final Logger LOG = Logger.getLogger(HypothesisGenerator.class.getName());

    private final HypothesisStore store;

    public HypothesisGenerator(HypothesisStore store) {
        this.store = store;
    }

    /** Generate one hypothesis and persist it. */
    public CausalHypothesis propose(String cause, String condition, String effect, String rationale) {
        if (cause == null || cause.isBlank() || effect == null || effect.isBlank()) return null;
        // Dedup: if there is already an open hypothesis with the same triple, reuse it
        for (CausalHypothesis open : store.open()) {
            if (eq(open.cause(), cause) && eq(open.effect(), effect) && eq(open.condition(), condition)) {
                return open;
            }
        }
        CausalHypothesis.Direction dir = inferDirection(cause + " " + effect);
        String action = "Beobachte Effekt '" + effect + "' nach Eintritt von '" + cause + "'";
        CausalHypothesis h = new CausalHypothesis(
                null,
                cause.trim(),
                condition == null ? "" : condition.trim(),
                effect.trim(),
                dir,
                0.6,
                rationale == null ? "Aus Surprise abgeleitet" : rationale,
                action,
                CausalHypothesis.Status.PROPOSED,
                null, null, null, 0.0, ""
        );
        CausalHypothesis saved = store.upsert(h);
        LOG.info("HypothesisGenerator: proposed '" + saved.cause() + " -> " + saved.effect()
                + "' (" + dir + ")");
        return saved;
    }

    private static boolean eq(String a, String b) {
        return Objects.equals(a == null ? "" : a.trim().toLowerCase(),
                              b == null ? "" : b.trim().toLowerCase());
    }

    private static CausalHypothesis.Direction inferDirection(String s) {
        String l = s.toLowerCase();
        if (l.contains("senken") || l.contains("weniger") || l.contains("less")
                || l.contains("decrease") || l.contains("drop")) {
            return CausalHypothesis.Direction.DOWN;
        }
        if (l.contains("erhöhen") || l.contains("mehr") || l.contains("more")
                || l.contains("increase") || l.contains("steigen")) {
            return CausalHypothesis.Direction.UP;
        }
        return CausalHypothesis.Direction.UP;
    }
}
