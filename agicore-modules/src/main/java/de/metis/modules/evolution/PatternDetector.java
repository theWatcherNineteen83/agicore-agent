package de.metis.modules.evolution;

import java.util.*;
import java.util.logging.Logger;

/**
 * Phase 12c — PatternDetector: erkennt wiederkehrende Muster in Metriken.
 *
 * <p>Analysiert die letzten N Snapshots aus {@link MetricTimeSeries}
 * und generiert Optimierungs-Hypothesen.
 */
public class PatternDetector {

    private static final Logger LOG = Logger.getLogger(PatternDetector.class.getName());
    private final List<String> detectedPatterns = new ArrayList<>();

    /**
     * Analysiert Snapshots und gibt erkannte Muster zurueck.
     */
    public List<Pattern> analyze(List<MetricTimeSeries.Snapshot> snapshots) {
        List<Pattern> patterns = new ArrayList<>();
        if (snapshots.size() < 5) return patterns;

        // 1. Success rate cycles
        double[] rates = snapshots.stream().mapToDouble(MetricTimeSeries.Snapshot::successRate).toArray();
        int cycles = countCycles(rates);
        if (cycles > 2) {
            var p = new Pattern("success_rate_oscillation",
                    "Success rate oscillates " + cycles + " times in " + rates.length + " samples. "
                    + "Possible cause: model contention or scheduling issue.",
                    "modules/planner/OllamaPlanner.java", 40);
            patterns.add(p);
            detectedPatterns.add(p.id());
        }

        // 2. Planning efficiency vs confidence correlation
        double[] planning = snapshots.stream().mapToDouble(MetricTimeSeries.Snapshot::planningEfficiency).toArray();
        double[] confidence = snapshots.stream().mapToDouble(MetricTimeSeries.Snapshot::confidence).toArray();
        double corr = correlation(planning, confidence);
        if (Math.abs(corr) > 0.7) {
            var p = new Pattern("planning_confidence_correlation",
                    "Planning efficiency and confidence are strongly correlated (r="
                    + String.format("%.2f", corr) + "). Improving one will likely improve the other.",
                    "kernel/meta/MetaCognition.java", 50);
            patterns.add(p);
            detectedPatterns.add(p.id());
        }

        // 3. Performance degradation over time
        if (rates.length >= 10) {
            double first10 = avg(rates, 0, 5);
            double last10 = avg(rates, rates.length - 5, rates.length);
            if (first10 > last10 + 0.1) {
                var p = new Pattern("gradual_performance_degradation",
                        "Success rate dropped from " + String.format("%.0f", first10 * 100)
                        + "% to " + String.format("%.0f", last10 * 100)
                        + "% over last " + rates.length + " samples.",
                        "kernel/self/BugTracker.java", 60);
                patterns.add(p);
                detectedPatterns.add(p.id());
            }
        }

        for (var p : patterns) {
            LOG.info("PatternDetector: " + p.id() + " prio=" + p.priority());
        }
        return patterns;
    }

    public List<String> recentPatterns() { return List.copyOf(detectedPatterns); }

    // ── Signal processing ────────────────────────────

    private int countCycles(double[] data) {
        int cycles = 0;
        for (int i = 2; i < data.length; i++) {
            if ((data[i-2] < data[i-1] && data[i-1] > data[i]) ||
                (data[i-2] > data[i-1] && data[i-1] < data[i])) {
                cycles++;
            }
        }
        return cycles / 2;
    }

    private double correlation(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        if (n < 3) return 0;
        double meanA = avg(a, 0, n);
        double meanB = avg(b, 0, n);
        double cov = 0, stdA = 0, stdB = 0;
        for (int i = 0; i < n; i++) {
            double da = a[i] - meanA;
            double db = b[i] - meanB;
            cov += da * db;
            stdA += da * da;
            stdB += db * db;
        }
        return cov / (Math.sqrt(stdA) * Math.sqrt(stdB) + 1e-10);
    }

    private double avg(double[] d, int from, int to) {
        double sum = 0;
        int n = Math.min(to, d.length) - from;
        if (n <= 0) return 0;
        for (int i = from; i < Math.min(to, d.length); i++) sum += d[i];
        return sum / n;
    }

    public record Pattern(String id, String description, String targetFile, int priority) {}
}
