package de.metis.kernel.world;

import java.util.*;

/**
 * Phase 10.4 — leichtgewichtige Counterfactual-Anfrage (twin-network-Approximation).
 *
 * <p>Antwortet auf "Was wäre passiert, wenn cause unter condition eingetreten wäre?"
 * indem die {@link CausalModel#predict(String, String, int)}-Antworten ausgewertet
 * und mit der besten Aktion ({@link CausalModel#bestAction}) verglichen werden.
 *
 * <p>Bewusst kein echter Pearl-Algorithmus (Abduktion/Action/Prediction) — das
 * würde ein vollständiges strukturelles Modell verlangen. Stattdessen liefern
 * wir eine pragmatische deutsche Erklärung samt Confidence.
 */
public class Counterfactual {

    private final CausalModel causal;

    public Counterfactual(CausalModel causal) {
        this.causal = causal;
    }

    public record Answer(
            String question,
            String predictedEffect,
            double confidence,
            int evidence,
            String comparativeAction,
            String explanation
    ) {}

    public Answer query(String cause, String condition, String effectOfInterest) {
        if (causal == null) return new Answer(
                "n/a", "kein CausalModel verfügbar", 0.0, 0, "n/a",
                "Counterfactual-Reasoning offline.");

        var preds = causal.predict(cause, condition, 5);
        String predictedEffect = effectOfInterest != null ? effectOfInterest : "";
        double confidence = 0.0;
        int evidence = 0;
        for (var p : preds) {
            if (effectOfInterest == null) {
                if (p.confidence() > confidence) {
                    confidence = p.confidence();
                    evidence = p.evidence();
                    predictedEffect = p.effect();
                }
            } else if (p.effect().equalsIgnoreCase(effectOfInterest)) {
                confidence = p.confidence();
                evidence = p.evidence();
                break;
            }
        }

        String alt = causal.bestAction(effectOfInterest != null ? effectOfInterest : predictedEffect, condition);
        String question = String.format(java.util.Locale.ROOT,
                "Was wäre passiert, wenn '%s' unter '%s' eingetreten wäre?",
                cause, condition == null ? "" : condition);
        String explanation;
        if (evidence == 0) {
            explanation = "Keine Evidenz im CausalModel — Counterfactual ist Spekulation.";
        } else {
            explanation = String.format(java.util.Locale.ROOT,
                    "Bei %d früheren Beobachtungen folgte '%s' mit Konfidenz %.2f.",
                    evidence, predictedEffect, confidence);
        }
        return new Answer(question, predictedEffect, confidence, evidence,
                alt == null ? "—" : alt, explanation);
    }
}
