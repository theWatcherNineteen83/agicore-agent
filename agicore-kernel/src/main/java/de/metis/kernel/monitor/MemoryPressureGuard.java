package de.metis.kernel.monitor;

import de.metis.kernel.world.WorldModel;
import de.metis.kernel.workspace.WorkspaceShadowLogger;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.logging.Logger;

/**
 * Heap-pressure monitor with automatic eviction of belief cache.
 * <p>
 * <b>Problem it solves (09.06.2026):</b> When the in-memory belief cache grows
 * past the configured -Xmx, GC pauses become extreme, which cascades into NIO
 * selector failures and permanent deadlock. This guard detects pressure early
 * and evicts cache entries before that happens.
 * <p>
 * <b>Deterministic — no LLM calls.</b> Pure JVM metrics + eviction logic.
 * Safe to run every tick.
 * <p>
 * <b>Pressure levels:</b>
 * <ul>
 *   <li>{@code GREEN < 0.75} — normal operation</li>
 *   <li>{@code YELLOW 0.75–0.85} — advisory, log once per transition</li>
 *   <li>{@code ORANGE 0.85–0.95} — evict half the cache, hint GC</li>
 *   <li>{@code RED > 0.95} — aggressive eviction (keep top 100), pause workspace log</li>
 * </ul>
 */
public final class MemoryPressureGuard {

    private static final Logger LOG = Logger.getLogger(MemoryPressureGuard.class.getName());

    /** Fraction of max heap at which we consider pressure. */
    private static final double YELLOW_THRESHOLD = 0.75;
    private static final double ORANGE_THRESHOLD = 0.85;
    private static final double RED_THRESHOLD = 0.95;

    /** Consecutive checks above threshold before acting (hysteresis). */
    private static final int CONSECUTIVE_TRIGGER = 3;

    /** How many beliefs to keep after ORANGE eviction. */
    private static final int ORANGE_KEEP = 2000;

    /** How many beliefs to keep after RED eviction. */
    private static final int RED_KEEP = 100;

    public enum Level {
        GREEN, YELLOW, ORANGE, RED
    }

    private final WorldModel worldModel;
    private final MemoryMXBean memoryBean;

    private Level currentLevel = Level.GREEN;
    private Level prevLevel = Level.GREEN;
    private int pressureCount = 0;
    private int orangeEvictions = 0;
    private int redEvictions = 0;
    private double lastHeapFraction = 0.0;
    private long lastCheckMs = 0;

    public MemoryPressureGuard(WorldModel worldModel) {
        this.worldModel = worldModel;
        this.memoryBean = ManagementFactory.getMemoryMXBean();
    }

    /**
     * Evaluate current heap pressure and take action if needed.
     * Call this once per tick (or every N ticks).
     *
     * @param workspaceShadow nullable — paused on RED to reduce I/O pressure
     */
    public void check(WorkspaceShadowLogger workspaceShadow) {
        lastCheckMs = System.currentTimeMillis();
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        double used = heap.getUsed();
        double max = heap.getMax();
        if (max <= 0) return; // no max set? skip
        lastHeapFraction = used / max;

        Level newLevel;
        if (lastHeapFraction >= RED_THRESHOLD) {
            newLevel = Level.RED;
        } else if (lastHeapFraction >= ORANGE_THRESHOLD) {
            newLevel = Level.ORANGE;
        } else if (lastHeapFraction >= YELLOW_THRESHOLD) {
            newLevel = Level.YELLOW;
        } else {
            newLevel = Level.GREEN;
        }

        if (newLevel != currentLevel) {
            prevLevel = currentLevel;
            currentLevel = newLevel;
            pressureCount = 0;
            LOG.info("MemoryPressure: " + prevLevel + " → " + currentLevel
                    + " (heap " + String.format("%.0f", lastHeapFraction * 100)
                    + "%, " + String.format("%.0f", used / (1024 * 1024))
                    + " MB / " + String.format("%.0f", max / (1024 * 1024)) + " MB)");
        } else {
            pressureCount++;
        }

        // Only act after hysteresis (consecutive checks at same level)
        if (pressureCount < CONSECUTIVE_TRIGGER) return;

        switch (currentLevel) {
            case ORANGE -> handleOrange();
            case RED    -> handleRed(workspaceShadow);
            case GREEN  -> handleGreen(workspaceShadow);
            // YELLOW is advisory only, no action
            default -> {}
        }
    }

    private void handleOrange() {
        int before = worldModel.beliefCount();
        worldModel.evictCacheToTarget(ORANGE_KEEP);
        int after = worldModel.beliefCount();
        orangeEvictions++;
        System.gc(); // hint the GC — Zing GenPauseless will respond
        LOG.warning("🔥 ORANGE pressure — evicted belief cache ("
                + before + " → " + after + " entries, orange eviction #" + orangeEvictions + ")");
    }

    private void handleRed(WorkspaceShadowLogger workspaceShadow) {
        int before = worldModel.beliefCount();
        worldModel.evictCacheToTarget(RED_KEEP);
        int after = worldModel.beliefCount();
        redEvictions++;
        System.gc();

        // Pause workspace log to reduce I/O pressure
        if (workspaceShadow != null && workspaceShadow.isEnabled()) {
            workspaceShadow.setEnabled(false);
            LOG.warning("🛑 WorkspaceShadowLogger paused (RED pressure)");
        }

        LOG.severe("🔴 RED pressure — aggressive eviction ("
                + before + " → " + after + " entries, red eviction #" + redEvictions + ")");
    }

    private void handleGreen(WorkspaceShadowLogger workspaceShadow) {
        // Re-enable workspace log if it was paused during RED
        if (workspaceShadow != null && !workspaceShadow.isEnabled() && prevLevel == Level.RED) {
            workspaceShadow.setEnabled(true);
            LOG.info("✅ WorkspaceShadowLogger re-enabled (GREEN)");
        }
    }

    // ── Public state ─────────────────────────────────────────────

    public Level level() { return currentLevel; }
    public double heapFraction() { return lastHeapFraction; }
    public int orangeEvictions() { return orangeEvictions; }
    public int redEvictions() { return redEvictions; }
    public long lastCheckMs() { return lastCheckMs; }

    public String summary() {
        return String.format("Heap %.0f%% %s (orange=%d red=%d)",
                lastHeapFraction * 100, currentLevel, orangeEvictions, redEvictions);
    }
}
