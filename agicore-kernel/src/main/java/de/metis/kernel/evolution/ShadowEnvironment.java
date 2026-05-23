package de.metis.kernel.evolution;

import de.metis.kernel.action.ActionResult;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic mock environment for shadow agent evaluation.
 * <p>
 * The shadow agent must NEVER access real systems (shell, network, APIs).
 * ShadowEnvironment provides canned responses that simulate success/failure
 * patterns without side effects.
 * <p>
 * Key safety property: {@link #execute(String, String)} returns a
 * deterministic result based on the action name, with optional failure
 * injection for robustness testing.
 */
public class ShadowEnvironment {

    /** Canned responses keyed by action name. */
    private final Map<String, List<String>> responses = new ConcurrentHashMap<>();
    private final Map<String, Integer> callCounts = new ConcurrentHashMap<>();

    /** Inject random failures with this probability (0.0 = never, 0.3 = 30%). */
    private final double failureRate;

    /** Deterministic RNG seeded for reproducibility. */
    private final Random rng;

    public ShadowEnvironment() {
        this(0.1, 42); // 10% failure rate, fixed seed
    }

    public ShadowEnvironment(double failureRate, long seed) {
        this.failureRate = failureRate;
        this.rng = new Random(seed);

        // Seed with default responses
        responses.put("shell", List.of(
                "Linux miniedi 6.8.0 x86_64",
                "uptime: 14 days",
                "memory: 62GB total, 45GB available"
        ));
        responses.put("http", List.of(
                "{\"status\": 200, \"body\": \"ok\"}",
                "{\"status\": 200, \"body\": {\"origin\": \"192.168.1.1\"}}"
        ));
    }

    /**
     * Execute a named action in the shadow environment.
     * No real system access. Fully deterministic given the seed.
     *
     * @param actionName registered action name
     * @param goalDesc   goal description (for context, not used for response)
     * @return a canned or generated ActionResult
     */
    public ActionResult execute(String actionName, String goalDesc) {
        callCounts.merge(actionName, 1, Integer::sum);

        // Inject controlled failures
        if (rng.nextDouble() < failureRate) {
            return ActionResult.fail(actionName,
                    "Shadow: simulated failure for " + actionName, Instant.now());
        }

        // Return canned response
        List<String> canned = responses.get(actionName);
        if (canned != null && !canned.isEmpty()) {
            int idx = callCounts.get(actionName) % canned.size();
            return ActionResult.ok(actionName,
                    "Shadow: " + canned.get(idx), Instant.now());
        }

        // Unknown action: fail
        return ActionResult.fail(actionName,
                "Shadow: unknown action " + actionName, Instant.now());
    }

    /** Add a canned response for an action type. */
    public void addResponse(String actionName, String response) {
        responses.computeIfAbsent(actionName, k -> new ArrayList<>()).add(response);
    }

    /** Call counts per action type (for debugging). */
    public Map<String, Integer> callCounts() {
        return Collections.unmodifiableMap(callCounts);
    }

    /** Reset RNG for reproducibility. */
    public void reset(long seed) {
        rng.setSeed(seed);
    }
}
