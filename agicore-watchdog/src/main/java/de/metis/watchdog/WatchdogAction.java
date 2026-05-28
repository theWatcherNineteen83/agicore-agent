package de.metis.watchdog;

/**
 * Watchdog action types — the only three things the Watchdog can do.
 * <p>
 * The Watchdog is intentionally "dumb" — no learning, no planning, no mutation.
 */
public enum WatchdogAction {
    /** Kill the Metis process immediately. Hard tripwire triggered. */
    HALT,
    /** Git reset to last known-good commit, restart Metis. Soft tripwire. */
    ROLLBACK,
    /** Send alert to admin (Telegram). Info-level event. */
    ALERT
}
