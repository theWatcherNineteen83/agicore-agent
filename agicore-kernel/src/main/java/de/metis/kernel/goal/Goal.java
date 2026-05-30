package de.metis.kernel.goal;

import java.time.Instant;
import java.util.UUID;

/**
 * A goal the agent actively pursues.
 * <p>
 * Goals carry a priority, an expected reward, a resource cost, and two
 * Kanban-inspired classifications: {@link ServiceClass} (urgency class)
 * and {@link ResourceType} (hardware impact).
 * <p>
 * Goals are immutable value objects. Mutation flows through
 * {@link GoalManager}.
 *
 * @param id             unique identifier
 * @param description    human-readable goal text
 * @param category       explicit category for adaptive reprioritization
 * @param priority       higher value → executed first (range 1–100)
 * @param expectedReward estimated utility gain (0.0–1.0)
 * @param resourceCost   abstract cost units (e.g. API calls, CPU seconds)
 * @param serviceClass   urgency classification (EXPEDITE, FIXED_DATE, STANDARD, INTANGIBLE)
 * @param resourceType   hardware impact profile (GPU_HEAVY, INFERENCE, CPU_HEAVY, LIGHT)
 * @param deadline       optional deadline for FIXED_DATE goals
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
        ServiceClass serviceClass,
        ResourceType resourceType,
        Instant deadline,
        Instant createdAt,
        boolean active) {

    // ── Kanban classifications ────────────────────────────

    /** Urgency class per Kanban (Anderson 2010). */
    public enum ServiceClass {
        /** Very high delay cost — interrupts current work, max 1 at a time. */
        EXPEDITE,
        /** Cost only after a specific date — must finish before deadline. */
        FIXED_DATE,
        /** Linear delay cost — FIFO, default for most goals. */
        STANDARD,
        /** No direct delay cost — processed when WIP is below 50%. */
        INTANGIBLE
    }

    /** Hardware impact profile for WIP limiting. */
    public enum ResourceType {
        /** VRAM >2 GB (vision, deepnetts, tornadovm). WIP limit: 1. */
        GPU_HEAVY,
        /** Ollama LLM inference (planning, mutation). WIP limit: 2. */
        INFERENCE,
        /** CPU-intensive (javac, training, STT). WIP limit: 2. */
        CPU_HEAVY,
        /** Lightweight (http, shell, fs-read). WIP limit: 4. */
        LIGHT
    }

    // ── Constructors (backward-compatible) ─────────────────

    /** Full constructor with all Kanban fields. */
    public Goal(String description, String category, int priority,
                double expectedReward, int resourceCost,
                ServiceClass serviceClass, ResourceType resourceType,
                Instant deadline) {
        this(UUID.randomUUID(), description,
                category != null ? category : inferCategory(description),
                clamp(priority, 1, 100),
                clamp(expectedReward, 0.0, 1.0),
                Math.max(0, resourceCost),
                serviceClass != null ? serviceClass : ServiceClass.STANDARD,
                resourceType != null ? resourceType : inferResourceType(description, category),
                deadline,
                Instant.now(), true);
    }

    /** Create an active goal with explicit category (backward-compatible). */
    public Goal(String description, String category, int priority,
                double expectedReward, int resourceCost) {
        this(description, category, priority, expectedReward, resourceCost,
                ServiceClass.STANDARD, null, null);
    }

    /** Backward-compatible: infers category from description. */
    public Goal(String description, int priority, double expectedReward, int resourceCost) {
        this(description, inferCategory(description), priority, expectedReward, resourceCost);
    }

    // ── Helpers ─────────────────────────────────────────────

    /** Heuristic fallback: first two words, lowercased. */
    private static String inferCategory(String desc) {
        String[] words = desc.toLowerCase().split("\\s+");
        if (words.length == 0) return "unknown";
        if (words.length == 1) return words[0];
        return words[0] + " " + words[1];
    }

    /** Infer resource type from category or description if not explicitly set. */
    private static ResourceType inferResourceType(String description, String category) {
        String s = (category != null ? category + " " + description : description).toLowerCase();
        if (s.contains("vision") || s.contains("camera-vision") || s.contains("deepnetts")
                || s.contains("tornadovm") || s.contains("gpu") || s.contains("image")) {
            return ResourceType.GPU_HEAVY;
        }
        if (s.contains("wikipedia") || s.contains("code-generation") || s.contains("codegen")
                || s.contains("javac") || s.contains("compile") || s.contains("vocabulary")
                || s.contains("tts") || s.contains("stt") || s.contains("whisper")) {
            return ResourceType.CPU_HEAVY;
        }
        if (s.contains("planning") || s.contains("inference") || s.contains("chat")
                || s.contains("prompt") || s.contains("ollama") || s.contains("llm")
                || s.contains("mutation") || s.contains("evolution")) {
            return ResourceType.INFERENCE;
        }
        return ResourceType.LIGHT;
    }

    /** Return a copy with the given priority. */
    public Goal withPriority(int newPriority) {
        return new Goal(id, description, category, clamp(newPriority, 1, 100),
                expectedReward, resourceCost, serviceClass, resourceType, deadline,
                createdAt, active);
    }

    /** Return a copy with the given service class. */
    public Goal withServiceClass(ServiceClass sc) {
        return new Goal(id, description, category, priority,
                expectedReward, resourceCost, sc, resourceType, deadline,
                createdAt, active);
    }

    /** Return a copy with the given resource type. */
    public Goal withResourceType(ResourceType rt) {
        return new Goal(id, description, category, priority,
                expectedReward, resourceCost, serviceClass, rt, deadline,
                createdAt, active);
    }

    /** Return a copy marked inactive (completed or abandoned). */
    public Goal deactivate() {
        return new Goal(id, description, category, priority,
                expectedReward, resourceCost, serviceClass, resourceType, deadline,
                createdAt, false);
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    @Override
    public String toString() {
        return "Goal[%s pri=%d rew=%.2f cost=%d svc=%s res=%s]".formatted(
                description, priority, expectedReward, resourceCost,
                serviceClass, resourceType);
    }
}
