package de.metis.kernel.goal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 9 — Long-Horizon-Wrapper um einen {@link Goal}.
 *
 * <p>Ergänzt um:
 * <ul>
 *   <li>Zeit-Horizont (TICK..STRATEGIC..LIFETIME)</li>
 *   <li>Parent/Children-Beziehung (Decomposition-Baum)</li>
 *   <li>Optionalen Deadline-/Commitment-Bezug</li>
 *   <li>Status für Multi-Tick-Verfolgung (PROPOSED, ACTIVE, BLOCKED, DONE, ABANDONED)</li>
 *   <li>Lifecycle-Statistiken (createdAt, lastReviewed, completedAt)</li>
 * </ul>
 *
 * <p>Bewusst <em>kein</em> Kanban-State (BACKLOG/READY/...) hier — der
 * Long-Horizon-Goal wird vom {@link HorizonPlanner} in Tick-Ebenen-Goals
 * zerlegt, und nur die landen auf dem Kanban-Board.
 */
public record LongHorizonGoal(
        UUID id,
        String title,
        String rationale,
        GoalHorizon horizon,
        Status status,
        UUID parentId,
        List<UUID> childIds,
        Instant createdAt,
        Instant deadline,
        Instant lastReviewed,
        Instant completedAt,
        double progress,
        int priority,
        String owner,
        List<String> tags
) {
    public enum Status { PROPOSED, ACTIVE, BLOCKED, DONE, ABANDONED }

    public LongHorizonGoal {
        if (id == null) id = UUID.randomUUID();
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title required");
        if (horizon == null) throw new IllegalArgumentException("horizon required");
        if (status == null) status = Status.PROPOSED;
        if (childIds == null) childIds = List.of();
        if (createdAt == null) createdAt = Instant.now();
        if (tags == null) tags = List.of();
        if (priority < 1) priority = 50;
        if (priority > 100) priority = 100;
        if (progress < 0.0) progress = 0.0;
        if (progress > 1.0) progress = 1.0;
        if (rationale == null) rationale = "";
        if (owner == null) owner = "metis";
    }

    public LongHorizonGoal withStatus(Status s) {
        return new LongHorizonGoal(id, title, rationale, horizon, s, parentId,
                childIds, createdAt, deadline,
                Instant.now(),
                s == Status.DONE ? Instant.now() : completedAt,
                s == Status.DONE ? 1.0 : progress,
                priority, owner, tags);
    }

    public LongHorizonGoal withProgress(double p) {
        return new LongHorizonGoal(id, title, rationale, horizon, status, parentId,
                childIds, createdAt, deadline, lastReviewed, completedAt,
                Math.max(0.0, Math.min(1.0, p)), priority, owner, tags);
    }

    public LongHorizonGoal withChild(UUID childId) {
        java.util.List<UUID> next = new java.util.ArrayList<>(childIds);
        if (!next.contains(childId)) next.add(childId);
        return new LongHorizonGoal(id, title, rationale, horizon, status, parentId,
                java.util.List.copyOf(next), createdAt, deadline, lastReviewed,
                completedAt, progress, priority, owner, tags);
    }

    public LongHorizonGoal withReviewedNow() {
        return new LongHorizonGoal(id, title, rationale, horizon, status, parentId,
                childIds, createdAt, deadline, Instant.now(), completedAt,
                progress, priority, owner, tags);
    }

    public boolean isOpen() {
        return status == Status.PROPOSED || status == Status.ACTIVE || status == Status.BLOCKED;
    }

    /** True if this goal is past its deadline AND not done. */
    public boolean isOverdue() {
        return deadline != null && isOpen() && Instant.now().isAfter(deadline);
    }
}
