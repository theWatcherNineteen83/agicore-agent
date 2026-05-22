package de.agicore.kernel.goal;

import java.time.Instant;
import java.util.UUID;

/**
 * A goal the agent actively pursues.
 * <p>
 * Goals carry a priority (higher = more urgent), an expected reward
 * (how much utility the agent expects upon completion), and a resource
 * cost estimate so the planner can bound effort.
 * <p>
 * Goals are immutable value objects. Mutation flows through
 * {@link GoalManager}.
 *
 * @param id             unique identifier
 * @param description    human-readable goal text
 * @param category       explicit category for adaptive reprioritization (e.g. "shell", "http")
 * @param priority       higher value → executed first (range 1–100)
 * @param expectedReward estimated utility gain (0.0–1.0)
 * @param resourceCost   abstract cost units (e.g. API calls, CPU seconds)
 * @param createdAt      when the goal was created
 * @param active         whether the goal is currently pursued
 */
public record Goal(
        UUID id,
        String description,
        String category,
        int priority,
        double expectedReward,
        int resourceCost,
        Instant createdAt,
        boolean active) {

    /** Create an active goal with explicit category. */
    public Goal(String description, String category, int priority,
                double expectedReward, int resourceCost) {
        this(UUID.randomUUID(), description,
                category != null ? category : inferCategory(description),
                clamp(priority, 1, 100),
                clamp(expectedReward, 0.0, 1.0),
                Math.max(0, resourceCost),
                Instant.now(), true);
    }

    /** Backward-compatible: infers category from description. */
    public Goal(String description, int priority, double expectedReward, int resourceCost) {
        this(description, inferCategory(description), priority, expectedReward, resourceCost);
    }

    /** Heuristic fallback: first two words, lowercased. */
    private static String inferCategory(String desc) {
        String[] words = desc.toLowerCase().split("\\s+");
        if (words.length == 0) return "unknown";
        if (words.length == 1) return words[0];
        return words[0] + " " + words[1];
    }

    /** Return a copy with the given priority. */
    public Goal withPriority(int newPriority) {
        return new Goal(id, description, category, clamp(newPriority, 1, 100),
                expectedReward, resourceCost, createdAt, active);
    }

    /** Return a copy marked inactive (completed or abandoned). */
    public Goal deactivate() {
        return new Goal(id, description, category, priority,
                expectedReward, resourceCost, createdAt, false);
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    @Override
    public String toString() {
        return "Goal[%s pri=%d rew=%.2f cost=%d]".formatted(
                description, priority, expectedReward, resourceCost);
    }
}
