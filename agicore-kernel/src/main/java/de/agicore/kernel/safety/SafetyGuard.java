package de.agicore.kernel.safety;

import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Watchdog that enforces resource limits on shadow agent evaluations.
 * <p>
 * Prevents:
 * <ul>
 *   <li>CPU exhaustion (max runtime per evaluation)</li>
 *   <li>Memory leaks (max ticks per evaluation)</li>
 *   <li>Deadlocks (timeout with forced termination)</li>
 *   <li>Runaway mutation (max evaluations per cycle)</li>
 * </ul>
 * <p>
 * The SafetyGuard is part of the immutable kernel. It can never be
 * disabled or bypassed by evolvable modules.
 */
public final class SafetyGuard {

    private static final Logger LOG = Logger.getLogger(SafetyGuard.class.getName());

    /** Maximum wall-clock time for a single shadow evaluation. */
    private final Duration maxRuntime;

    /** Maximum ticks per shadow evaluation. */
    private final int maxTicks;

    /** Maximum mutation attempts per evolution cycle. */
    private final int maxMutationsPerCycle;

    /** Maximum consecutive failures before evolution pauses. */
    private final int maxConsecutiveFailures;

    private int mutationCount = 0;
    private int consecutiveFailures = 0;
    private Instant cycleStart;

    public SafetyGuard() {
        this(Duration.ofSeconds(30), 500, 10, 5);
    }

    public SafetyGuard(Duration maxRuntime, int maxTicks,
                       int maxMutationsPerCycle, int maxConsecutiveFailures) {
        this.maxRuntime = maxRuntime;
        this.maxTicks = maxTicks;
        this.maxMutationsPerCycle = maxMutationsPerCycle;
        this.maxConsecutiveFailures = maxConsecutiveFailures;
    }

    /** Begin a new evolution cycle. Resets counters. */
    public void beginCycle() {
        mutationCount = 0;
        consecutiveFailures = 0;
        cycleStart = Instant.now();
    }

    /**
     * Check whether a new mutation attempt is permitted.
     *
     * @return true if mutation can proceed
     * @throws SafetyViolationException if limits exceeded
     */
    public boolean allowMutation() throws SafetyViolationException {
        mutationCount++;

        if (mutationCount > maxMutationsPerCycle) {
            throw new SafetyViolationException(
                    "Mutation limit exceeded: " + maxMutationsPerCycle + " per cycle");
        }

        if (consecutiveFailures >= maxConsecutiveFailures) {
            throw new SafetyViolationException(
                    "Consecutive failure limit reached: " + maxConsecutiveFailures
                            + " — evolution paused");
        }

        if (cycleStart != null) {
            Duration elapsed = Duration.between(cycleStart, Instant.now());
            if (elapsed.compareTo(maxRuntime) > 0) {
                throw new SafetyViolationException(
                        "Cycle runtime exceeded: " + elapsed.toSeconds() + "s > "
                                + maxRuntime.toSeconds() + "s");
            }
        }

        return true;
    }

    /** Record a successful mutation. Resets failure counter. */
    public void recordSuccess() {
        consecutiveFailures = 0;
    }

    /** Record a failed mutation. Increments failure counter. */
    public void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= maxConsecutiveFailures) {
            LOG.warning(() -> consecutiveFailures
                    + " consecutive failures — evolution will pause");
        }
    }

    /** Maximum ticks allowed for a single shadow evaluation. */
    public int maxTicks() { return maxTicks; }

    /** Maximum runtime for a single shadow evaluation. */
    public Duration maxRuntime() { return maxRuntime; }

    public int mutationCount() { return mutationCount; }
    public int consecutiveFailures() { return consecutiveFailures; }

    /** Thrown when a safety limit is exceeded. */
    public static final class SafetyViolationException extends Exception {
        public SafetyViolationException(String message) {
            super(message);
        }
    }
}
