package de.metis.kernel.goal;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Tracks a goal's journey through the Kanban board.
 * <p>
 * Measures:
 * <ul>
 *   <li>Lead Time — created to completed (customer perspective)</li>
 *   <li>Cycle Time — pulled into IN_PROGRESS to completed (process perspective)</li>
 *   <li>Wait Time — created to pulled (time in backlog/ready)</li>
 *   <li>Retries — how many times the goal was requeued</li>
 * </ul>
 */
public class GoalFlowMetrics {

    private final UUID goalId;
    private final String description;
    private final Goal.ServiceClass serviceClass;
    private final Goal.ResourceType resourceType;
    private final Instant created;

    private Instant promotedToReady;
    private Instant pulledToInProgress;
    private Instant completed;
    private Instant failed; // last failure timestamp
    private int retries;
    private Duration leadTime;
    private Duration cycleTime;

    public GoalFlowMetrics(UUID goalId, String description,
                           Goal.ServiceClass serviceClass,
                           Goal.ResourceType resourceType,
                           Instant created,
                           Instant promotedToReady,
                           Instant pulledToInProgress,
                           Instant completed,
                           Instant failed,
                           int retries) {
        this.goalId = goalId;
        this.description = description;
        this.serviceClass = serviceClass;
        this.resourceType = resourceType;
        this.created = created;
        this.promotedToReady = promotedToReady;
        this.pulledToInProgress = pulledToInProgress;
        this.completed = completed;
        this.failed = failed;
        this.retries = retries;
    }

    // ── State transitions ────────────────────────────────────

    public void setPromotedToReady(Instant t) { this.promotedToReady = t; }
    public void setPulledToInProgress(Instant t) { this.pulledToInProgress = t; }

    public void setCompleted(Instant t) {
        this.completed = t;
        computeLeadTime();
    }

    public void setFailed(Instant t) { this.failed = t; }
    public void incrementRetries() { this.retries++; }

    /** Compute lead time and cycle time from the timestamps. */
    public void computeLeadTime() {
        if (created != null && completed != null) {
            this.leadTime = Duration.between(created, completed);
        }
        if (pulledToInProgress != null && completed != null) {
            this.cycleTime = Duration.between(pulledToInProgress, completed);
        }
    }

    // ── Derived metrics ───────────────────────────────────────

    /** Total time from creation to completion (customer view). */
    public Duration leadTime() {
        if (leadTime != null) return leadTime;
        if (completed != null) return Duration.between(created, completed);
        return Duration.between(created, Instant.now());
    }

    /** Time from pull into IN_PROGRESS to now/completion (process view). */
    public Duration cycleTime() {
        if (cycleTime != null) return cycleTime;
        if (pulledToInProgress == null) return Duration.ZERO;
        return Duration.between(pulledToInProgress,
                completed != null ? completed : Instant.now());
    }

    /** Time spent waiting in backlog/ready. */
    public Duration waitTime() {
        Instant start = created;
        Instant end = pulledToInProgress != null ? pulledToInProgress : Instant.now();
        return Duration.between(start, end);
    }

    // ── Getters ───────────────────────────────────────────────

    public UUID goalId() { return goalId; }
    public String description() { return description; }
    public Goal.ServiceClass serviceClass() { return serviceClass; }
    public Goal.ResourceType resourceType() { return resourceType; }
    public Instant created() { return created; }
    public Instant promotedToReady() { return promotedToReady; }
    public Instant pulledToInProgress() { return pulledToInProgress; }
    public Instant completed() { return completed; }
    public Instant failed() { return failed; }
    public int getRetries() { return retries; }
    public int retries() { return retries; }

    public boolean isDone() { return completed != null; }

    @Override
    public String toString() {
        return "GoalFlowMetrics[" + description
                + " svc=" + serviceClass
                + " res=" + resourceType
                + " lead=" + (leadTime != null ? formatDuration(leadTime) : "-")
                + " cycle=" + (cycleTime != null ? formatDuration(cycleTime) : "-")
                + " retries=" + retries + "]";
    }

    private static String formatDuration(Duration d) {
        long s = d.getSeconds();
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m" + (s % 60) + "s";
        return (s / 3600) + "h" + ((s % 3600) / 60) + "m";
    }
}
