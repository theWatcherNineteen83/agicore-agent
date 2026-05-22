package de.agicore.agent.action;

import java.time.Duration;
import java.time.Instant;

/**
 * Immutable result envelope for any {@link Action}.
 * <p>
 * Captures success/failure, timing, and an optional structured body so
 * downstream modules (planner, meta-cognition) can evaluate action quality
 * without knowing the action type.
 *
 * @param name      action label for traceability
 * @param success   whether the action completed without error
 * @param body      human-readable output or serialised JSON
 * @param error     exception message if {@code success == false}
 * @param startedAt wall-clock start
 * @param duration  elapsed wall time
 */
public record ActionResult(
        String name,
        boolean success,
        String body,
        String error,
        Instant startedAt,
        Duration duration) {

    /** Factory for a successful result. */
    public static ActionResult ok(String name, String body, Instant startedAt) {
        return new ActionResult(name, true, body, null, startedAt,
                Duration.between(startedAt, Instant.now()));
    }

    /** Factory for a failed result. */
    public static ActionResult fail(String name, String error, Instant startedAt) {
        return new ActionResult(name, false, null, error, startedAt,
                Duration.between(startedAt, Instant.now()));
    }
}
