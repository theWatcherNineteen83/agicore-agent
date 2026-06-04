package de.metis.modules.evolution;

import java.util.*;
import java.util.logging.Logger;

/**
 * Phase 12b — FeatureFlag-System.
 *
 * <p>Neue generierte Features starten deaktiviert. Nach 1h Monitoring
 * werden sie automatisch aktiviert (wenn keine Regression).
 */
public class FeatureFlag {

    private static final Logger LOG = Logger.getLogger(FeatureFlag.class.getName());

    private final Map<String, FeatureStatus> flags = new LinkedHashMap<>();
    private static final long MONITOR_PERIOD_MS = 3600_000; // 1h

    public record FeatureStatus(
            String id,
            boolean enabled,
            long createdAt,
            long enabledAt,
            double preSuccessRate,
            double postSuccessRate
    ) {
        public boolean isStable() {
            return System.currentTimeMillis() - createdAt > MONITOR_PERIOD_MS;
        }
    }

    /**
     * Registriert ein neues Feature als deaktiviert.
     */
    public synchronized FeatureStatus register(String id, double currentSuccessRate) {
        var status = new FeatureStatus(id, false, System.currentTimeMillis(),
                0, currentSuccessRate, 0.0);
        flags.put(id, status);
        LOG.info("FeatureFlag: registered " + id + " (disabled, pre-rate="
                + String.format("%.2f", currentSuccessRate) + ")");
        return status;
    }

    /**
     * Prueft ob ein Feature nach 1h Monitoring aktiviert werden kann.
     */
    public synchronized FeatureStatus checkAndEnable(String id, double currentSuccessRate) {
        var existing = flags.get(id);
        if (existing == null) return register(id, currentSuccessRate);
        if (existing.enabled()) return existing;
        if (!existing.isStable()) return existing;

        // No regression? Enable it.
        double drop = existing.preSuccessRate() - currentSuccessRate;
        if (drop < 0.1) { // max 10% regression
            var enabled = new FeatureStatus(id, true, existing.createdAt(),
                    System.currentTimeMillis(), existing.preSuccessRate(), currentSuccessRate);
            flags.put(id, enabled);
            LOG.info("FeatureFlag: enabled " + id + " (post-rate="
                    + String.format("%.2f", currentSuccessRate) + ")");
            return enabled;
        }
        LOG.warning("FeatureFlag: BLOCKED " + id + " — regression "
                + String.format("%.2f", drop));
        return existing;
    }

    public List<FeatureStatus> all() { return List.copyOf(flags.values()); }
    public int activeCount() { return (int) flags.values().stream().filter(FeatureStatus::enabled).count(); }
}
