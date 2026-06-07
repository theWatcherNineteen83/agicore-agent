package de.metis.kernel.safety;

import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Phase 6+7 — Planner Health Guard (Sprint #1, 07.06.2026).
 *
 * <p>Tripwire über zwei strukturelle Planner-Drift-Symptome:
 * <ol>
 *   <li><b>Empty-Plan-Quote zu hoch</b> — Planner liefert {@code null}/leere Pläne über Schwellwert</li>
 *   <li><b>Action-Konzentration zu hoch</b> — eine Action dominiert den Mix
 *       (z.B. seit Phase 3.5 dominiert sensor-bridge: 84 von 105 valid plans)</li>
 * </ol>
 *
 * <p>Liefert {@link HealthReport} mit Severity OK | WARN | CRITICAL.
 * Wird vom AgentMain in der Status-Schleife abgefragt; CRITICAL kann von
 * Watchdog/Initiative-Engine als Signal gelesen werden.
 *
 * <p>Kein LLM, deterministisch, kein I/O — nur Zähler-Auswertung.
 *
 * <p>Designgrund (Code-Reality-Check 07.06. 23:30): {@code emptyPlanCount} und
 * {@code actionUsageCount} wurden bereits in {@code OllamaPlanner} getrackt
 * und in {@code /api/status} exposed, aber kein Tripwire wertete sie aus.
 */
public class PlannerHealthGuard {

    private static final Logger LOG = Logger.getLogger(PlannerHealthGuard.class.getName());

    /** Default: Empty-Plan-Quote ab 20% → WARN, ab 35% → CRITICAL. */
    public static final double DEFAULT_EMPTY_WARN = 0.20;
    public static final double DEFAULT_EMPTY_CRITICAL = 0.35;

    /** Default: Top-Action-Dominanz ab 70% → WARN, ab 85% → CRITICAL. */
    public static final double DEFAULT_DOMINANCE_WARN = 0.70;
    public static final double DEFAULT_DOMINANCE_CRITICAL = 0.85;

    /** Mindestanzahl Pläne, bevor Quoten bewertet werden (Warmup-Schutz). */
    public static final int MIN_SAMPLE_SIZE = 20;

    private final double emptyWarn;
    private final double emptyCritical;
    private final double dominanceWarn;
    private final double dominanceCritical;
    private final int minSampleSize;

    public PlannerHealthGuard() {
        this(DEFAULT_EMPTY_WARN, DEFAULT_EMPTY_CRITICAL,
             DEFAULT_DOMINANCE_WARN, DEFAULT_DOMINANCE_CRITICAL, MIN_SAMPLE_SIZE);
    }

    public PlannerHealthGuard(double emptyWarn, double emptyCritical,
                              double dominanceWarn, double dominanceCritical,
                              int minSampleSize) {
        if (emptyWarn < 0 || emptyWarn > 1) {
            throw new IllegalArgumentException("emptyWarn out of range: " + emptyWarn);
        }
        if (emptyCritical < emptyWarn || emptyCritical > 1) {
            throw new IllegalArgumentException("emptyCritical must be ≥ emptyWarn and ≤ 1");
        }
        if (dominanceWarn < 0 || dominanceWarn > 1) {
            throw new IllegalArgumentException("dominanceWarn out of range: " + dominanceWarn);
        }
        if (dominanceCritical < dominanceWarn || dominanceCritical > 1) {
            throw new IllegalArgumentException("dominanceCritical must be ≥ dominanceWarn and ≤ 1");
        }
        if (minSampleSize < 1) {
            throw new IllegalArgumentException("minSampleSize must be ≥ 1");
        }
        this.emptyWarn = emptyWarn;
        this.emptyCritical = emptyCritical;
        this.dominanceWarn = dominanceWarn;
        this.dominanceCritical = dominanceCritical;
        this.minSampleSize = minSampleSize;
    }

    /**
     * Wertet die Planner-Metriken aus.
     *
     * @param totalPlans         insgesamt versuchte Planungen (inkl. leerer)
     * @param emptyPlans         davon leere Pläne
     * @param actionUsageCount   Action-Name → Anzahl gewählter Pläne (nur valide)
     * @return strukturierter HealthReport
     */
    public HealthReport check(int totalPlans, int emptyPlans,
                              Map<String, Integer> actionUsageCount) {
        Objects.requireNonNull(actionUsageCount, "actionUsageCount");

        // Warmup: zu wenig Datenpunkte → OK ohne Aussage
        if (totalPlans < minSampleSize) {
            return new HealthReport(
                    Severity.OK,
                    List.of(),
                    0.0, 0.0, "(warmup)",
                    totalPlans, emptyPlans, actionUsageCount.size(),
                    Instant.now());
        }

        double emptyRatio = totalPlans == 0 ? 0.0 : (double) emptyPlans / totalPlans;

        int totalActionUses = actionUsageCount.values().stream()
                .mapToInt(Integer::intValue).sum();
        String topAction = "(none)";
        int topUses = 0;
        for (var e : actionUsageCount.entrySet()) {
            if (e.getValue() > topUses) {
                topUses = e.getValue();
                topAction = e.getKey();
            }
        }
        double dominance = totalActionUses == 0 ? 0.0 : (double) topUses / totalActionUses;

        List<String> findings = new ArrayList<>();
        Severity sev = Severity.OK;

        if (emptyRatio >= emptyCritical) {
            findings.add(String.format(java.util.Locale.ROOT,
                    "CRITICAL emptyRatio=%.2f >= %.2f (%d/%d empty plans)",
                    emptyRatio, emptyCritical, emptyPlans, totalPlans));
            sev = Severity.CRITICAL;
        } else if (emptyRatio >= emptyWarn) {
            findings.add(String.format(java.util.Locale.ROOT,
                    "WARN emptyRatio=%.2f >= %.2f (%d/%d empty plans)",
                    emptyRatio, emptyWarn, emptyPlans, totalPlans));
            if (sev == Severity.OK) sev = Severity.WARN;
        }

        if (dominance >= dominanceCritical && totalActionUses >= minSampleSize) {
            findings.add(String.format(java.util.Locale.ROOT,
                    "CRITICAL action-dominance=%.2f >= %.2f (action '%s' = %d/%d)",
                    dominance, dominanceCritical, topAction, topUses, totalActionUses));
            sev = Severity.CRITICAL;
        } else if (dominance >= dominanceWarn && totalActionUses >= minSampleSize) {
            findings.add(String.format(java.util.Locale.ROOT,
                    "WARN action-dominance=%.2f >= %.2f (action '%s' = %d/%d)",
                    dominance, dominanceWarn, topAction, topUses, totalActionUses));
            if (sev == Severity.OK) sev = Severity.WARN;
        }

        // Phase-Action-Starvation: wenn nur 1 Action genutzt wird → CRITICAL,
        // unabhängig von Schwellwert (das ist der Live-Befund 07.06.: sensor-bridge solo).
        if (actionUsageCount.size() == 1 && totalActionUses >= minSampleSize) {
            findings.add("CRITICAL action-starvation: only 1 distinct action in use ("
                    + topAction + ")");
            sev = Severity.CRITICAL;
        }

        HealthReport report = new HealthReport(
                sev, List.copyOf(findings),
                emptyRatio, dominance, topAction,
                totalPlans, emptyPlans, actionUsageCount.size(),
                Instant.now());

        if (sev != Severity.OK) {
            LOG.warning("PlannerHealthGuard: " + sev + " — " + String.join("; ", findings));
        }
        return report;
    }

    public enum Severity { OK, WARN, CRITICAL }

    public record HealthReport(
            Severity severity,
            List<String> findings,
            double emptyRatio,
            double topActionDominance,
            String topAction,
            int totalPlans,
            int emptyPlans,
            int distinctActions,
            Instant at) {

        public boolean isOk() { return severity == Severity.OK; }
        public boolean isWarn() { return severity == Severity.WARN; }
        public boolean isCritical() { return severity == Severity.CRITICAL; }

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"severity\":\"").append(severity).append("\"")
              .append(",\"emptyRatio\":").append(String.format(java.util.Locale.ROOT, "%.3f", emptyRatio))
              .append(",\"topActionDominance\":").append(String.format(java.util.Locale.ROOT, "%.3f", topActionDominance))
              .append(",\"topAction\":\"").append(escape(topAction)).append("\"")
              .append(",\"totalPlans\":").append(totalPlans)
              .append(",\"emptyPlans\":").append(emptyPlans)
              .append(",\"distinctActions\":").append(distinctActions)
              .append(",\"at\":\"").append(at).append("\"")
              .append(",\"findings\":[");
            for (int i = 0; i < findings.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(escape(findings.get(i))).append("\"");
            }
            sb.append("]}");
            return sb.toString();
        }

        private static String escape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        }
    }
}
