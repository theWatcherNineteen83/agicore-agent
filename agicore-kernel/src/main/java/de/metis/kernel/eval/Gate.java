package de.metis.kernel.eval;

/**
 * Gate severity for eval metrics.
 * <p>
 * HARD: failure blocks promotion; triggers ROLLBACK via Watchdog.<br>
 * SOFT: advisory flag; does not block.
 */
public enum Gate {
    HARD,
    SOFT
}
