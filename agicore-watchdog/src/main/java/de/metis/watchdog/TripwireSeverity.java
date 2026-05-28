package de.metis.watchdog;

/**
 * Tripwire severity levels.
 * <p>
 * HARD → immediate HALT (process kill).<br>
 * SOFT → ROLLBACK to last known-good commit + flag for human review.<br>
 * INFO → ALERT only (notification, no action).
 */
public enum TripwireSeverity {
    HARD,
    SOFT,
    INFO
}
