package de.metis.modules.evolution;

import java.util.*;
import java.util.logging.Logger;

/**
 * Phase 12c — Rolling Time Series fuer Metrik-Ueberwachung.
 *
 * <p>Speichert die letzten N Metrik-Snapshots und erkennt Trends:
 * <ul>
 *   <li>Delta > 20% unter 24h-Durchschnitt -> Alert</li>
 *   <li>Muster-Wiedererkennung (gleicher Fehlertyp hauefig)</li>
 * </ul>
 */
public class MetricTimeSeries {

    private static final Logger LOG = Logger.getLogger(MetricTimeSeries.class.getName());

    private static final int MAX_SAMPLES = 100;
    private final Deque<Snapshot> samples = new ArrayDeque<>(MAX_SAMPLES);
    private final List<String> alerts = new ArrayList<>();

    public record Snapshot(
            long tick,
            double planningEfficiency,
            double successRate,
            double confidence,
            int beliefCount,
            int openGoals,
            double llmSuccessRate
    ) {}

    /**
     * Fuegt einen Metrik-Snapshot hinzu und gibt Alarme zurueck.
     */
    public List<String> record(double planningEff, double successRate, double confidence,
                                int beliefCount, int openGoals, double llmSuccessRate) {
        var snap = new Snapshot(
                System.currentTimeMillis(), planningEff, successRate, confidence,
                beliefCount, openGoals, llmSuccessRate);
        samples.addLast(snap);
        while (samples.size() > MAX_SAMPLES) samples.removeFirst();

        return detectAnomalies(snap);
    }

    private List<String> detectAnomalies(Snapshot snap) {
        List<String> result = new ArrayList<>();

        if (samples.size() < 10) return result; // not enough data

        double avgSuccess = samples.stream()
                .mapToDouble(Snapshot::successRate).average().orElse(1.0);
        double avgPlanning = samples.stream()
                .mapToDouble(Snapshot::planningEfficiency).average().orElse(1.0);

        // Success rate drop > 20% below average
        if (snap.successRate() < avgSuccess * 0.8 && avgSuccess > 0.1) {
            String alert = "METRIC_ALERT: successRate=%.2f vs avg=%.2f (%.0f%% drop)"
                    .formatted(snap.successRate(), avgSuccess,
                            (1 - snap.successRate() / avgSuccess) * 100);
            result.add(alert);
            alerts.add(alert);
            LOG.warning("MetricTimeSeries: " + alert);
        }

        // Planning efficiency drop
        if (snap.planningEfficiency() < avgPlanning * 0.7 && avgPlanning > 0.1) {
            String alert = "METRIC_ALERT: planningEfficiency=%.2f vs avg=%.2f"
                    .formatted(snap.planningEfficiency(), avgPlanning);
            result.add(alert);
            alerts.add(alert);
            LOG.warning("MetricTimeSeries: " + alert);
        }

        // Keep alerts bounded
        while (alerts.size() > 50) alerts.removeFirst();

        return result;
    }

    public List<String> recentAlerts() { return List.copyOf(alerts); }
    public int sampleCount() { return samples.size(); }
    public List<Snapshot> recent(int n) {
        return samples.stream()
                .skip(Math.max(0, samples.size() - n))
                .toList();
    }
}
