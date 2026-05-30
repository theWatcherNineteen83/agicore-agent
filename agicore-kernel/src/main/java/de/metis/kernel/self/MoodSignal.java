package de.metis.kernel.self;

import de.metis.kernel.metrics.FitnessSignal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 8.3 — MoodSignal.
 *
 * <p>Aggregiert die FitnessSignal-Werte und externe Faktoren (Eval-Gate,
 * Goal-Erfolg, Surprise) zu einer einfachen Stimmungs-Karte mit vier Achsen:
 * <ul>
 *   <li><b>energy</b> — Throughput / Aktivität</li>
 *   <li><b>satisfaction</b> — Goal-Completion + Eval-Gate</li>
 *   <li><b>confidence</b> — currentFitness + Self-Kalibrierung</li>
 *   <li><b>curiosity</b> — recent Surprise-Rate</li>
 * </ul>
 *
 * <p>Bewusst <em>kein</em> Sentiment-LLM. Deterministisch, leicht, pro Tick fähig.
 */
public class MoodSignal {

    private static final double ALPHA = 0.1;

    private double energy = 0.5;
    private double satisfaction = 0.5;
    private double confidence = 0.5;
    private double curiosity = 0.5;

    public synchronized void update(FitnessSignal fitness,
                                    double goalSuccessRate,
                                    double evalGateOk,
                                    double recentSurpriseRate,
                                    double recentEnergy) {
        if (fitness != null) {
            confidence = ema(confidence, clamp(fitness.currentFitness()));
        }
        energy = ema(energy, clamp(recentEnergy));
        curiosity = ema(curiosity, clamp(recentSurpriseRate));
        double sat = clamp(0.7 * goalSuccessRate + 0.3 * evalGateOk);
        satisfaction = ema(satisfaction, sat);
    }

    public synchronized Map<String, Double> snapshot() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("energy", round(energy));
        m.put("satisfaction", round(satisfaction));
        m.put("confidence", round(confidence));
        m.put("curiosity", round(curiosity));
        return m;
    }

    public synchronized String label() {
        StringBuilder sb = new StringBuilder();
        sb.append(satisfaction > 0.7 ? "zufrieden" : satisfaction < 0.3 ? "frustriert" : "neutral");
        sb.append(", ");
        sb.append(curiosity > 0.7 ? "sehr neugierig" : curiosity < 0.3 ? "wenig neugierig" : "neugierig");
        if (confidence < 0.3) sb.append(", verunsichert");
        if (energy < 0.3) sb.append(", müde");
        return sb.toString();
    }

    private static double ema(double prev, double next) { return prev + ALPHA * (next - prev); }
    private static double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
