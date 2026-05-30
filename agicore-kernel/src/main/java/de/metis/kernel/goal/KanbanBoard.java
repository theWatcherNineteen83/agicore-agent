package de.metis.kernel.goal;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Logger;

/**
 * Kanban-inspired goal board with WIP limits per resource type.
 * <p>
 * Four columns:
 * <pre>
 *   BACKLOG → READY → IN_PROGRESS → DONE
 * </pre>
 * <p>
 * WIP limits are enforced per {@link Goal.ResourceType}:
 * <ul>
 *   <li>GPU_HEAVY: 1</li>
 *   <li>INFERENCE: 2</li>
 *   <li>CPU_HEAVY: 2</li>
 *   <li>LIGHT: 4</li>
 * </ul>
 * <p>
 * Service classes determine pull order:
 * <ul>
 *   <li>EXPEDITE: always pulled first, bypasses WIP limit (max 1 at a time)</li>
 *   <li>FIXED_DATE: pulled before STANDARD when deadline is near</li>
 *   <li>STANDARD: FIFO from READY</li>
 *   <li>INTANGIBLE: only pulled when total WIP &lt; 50% of capacity</li>
 * </ul>
 * <p>
 * Littles Gesetz: Durchlaufzeit = WIP / Durchsatz.
 * WIP halbieren = Durchlaufzeit halbieren.
 */
public class KanbanBoard {

    private static final Logger LOG = Logger.getLogger(KanbanBoard.class.getName());

    public enum Column { BACKLOG, READY, IN_PROGRESS, DONE }

    // ── Per-resource-type WIP limits ──────────────────────
    private static final Map<Goal.ResourceType, Integer> WIP_LIMITS = Map.of(
            Goal.ResourceType.GPU_HEAVY, 1,
            Goal.ResourceType.INFERENCE, 2,
            Goal.ResourceType.CPU_HEAVY, 2,
            Goal.ResourceType.LIGHT, 4);

    // ── Columns ───────────────────────────────────────────
    private final Deque<Goal> backlog = new ConcurrentLinkedDeque<>();
    private final Deque<Goal> ready = new ConcurrentLinkedDeque<>();
    private final Map<UUID, Goal> inProgress = new ConcurrentHashMap<>();
    private final Deque<GoalFlowMetrics> done = new ConcurrentLinkedDeque<>();
    private static final int MAX_DONE = 500;

    // ── Metrics tracking ──────────────────────────────────
    private final Map<UUID, GoalFlowMetrics> metrics = new ConcurrentHashMap<>();
    private int totalCompleted = 0;
    private int totalExpedites = 0;

    // ── Expedite slot ─────────────────────────────────────
    private volatile boolean expediteSlotInUse = false;

    /**
     * Add a goal to the BACKLOG column.
     * Goals stay in backlog until pull criteria are met (resources available).
     */
    public void add(Goal goal) {
        boolean isExpedite = goal.serviceClass() == Goal.ServiceClass.EXPEDITE;
        if (isExpedite && expediteSlotInUse) {
            LOG.warning("Expedite slot already in use — demoting second EXPEDITE to STANDARD");
            goal = goal.withServiceClass(Goal.ServiceClass.STANDARD);
        }

        final Goal finalGoal = goal;
        backlog.addLast(finalGoal);
        metrics.put(finalGoal.id(), new GoalFlowMetrics(finalGoal.id(),
                finalGoal.description(), finalGoal.serviceClass(), finalGoal.resourceType(),
                Instant.now(), null, null, null, null, 0));

        if (isExpedite) {
            expediteSlotInUse = true;
        }

        LOG.info(() -> "Kanban: BACKLOG ← " + finalGoal.description()
                + " [svc=" + finalGoal.serviceClass() + " res=" + finalGoal.resourceType() + "]");
    }

    /**
     * Try to pull a goal from READY into IN_PROGRESS.
     * <p>
     * Pull order:
     * <ol>
     *   <li>EXPEDITE goals (always, bypasses WIP)</li>
     *   <li>FIXED_DATE with imminent deadline (within 5 minutes)</li>
     *   <li>STANDARD goals (FIFO, resource-constrained)</li>
     *   <li>INTANGIBLE (only if total WIP &lt; 50% capacity)</li>
     * </ol>
     *
     * @return the pulled goal, or null if nothing can be pulled
     */
    public Goal pull() {
        // 1. EXPEDITE — always, bypasses WIP
        Goal expedite = findAndRemove(ready, g ->
                g.serviceClass() == Goal.ServiceClass.EXPEDITE);
        if (expedite != null) {
            return moveToInProgress(expedite);
        }

        // 2. FIXED_DATE with imminent deadline
        Instant now = Instant.now();
        Goal urgent = findAndRemove(ready, g ->
                g.serviceClass() == Goal.ServiceClass.FIXED_DATE
                        && g.deadline() != null
                        && Duration.between(now, g.deadline()).toMinutes() < 5);
        if (urgent != null && canPull(urgent.resourceType())) {
            return moveToInProgress(urgent);
        }

        // 3. STANDARD — FIFO, resource-constrained
        Goal standard = findAndRemove(ready, g ->
                g.serviceClass() == Goal.ServiceClass.STANDARD
                        && canPull(g.resourceType()));
        if (standard != null) {
            return moveToInProgress(standard);
        }

        // 4. INTANGIBLE — only if WIP below 50%
        if (totalWipPercent() < 50) {
            Goal intangible = findAndRemove(ready, g ->
                    g.serviceClass() == Goal.ServiceClass.INTANGIBLE
                            && canPull(g.resourceType()));
            if (intangible != null) {
                return moveToInProgress(intangible);
            }
        }

        return null; // nothing pullable
    }

    /**
     * Check whether a resource type can accept one more goal.
     */
    private boolean canPull(Goal.ResourceType type) {
        int limit = WIP_LIMITS.getOrDefault(type, 3);
        long current = inProgress.values().stream()
                .filter(g -> g.resourceType() == type)
                .count();
        return current < limit;
    }

    /**
     * Move a goal from READY to IN_PROGRESS.
     */
    private Goal moveToInProgress(Goal goal) {
        inProgress.put(goal.id(), goal);
        GoalFlowMetrics m = metrics.get(goal.id());
        if (m != null) {
            m.setPulledToInProgress(Instant.now());
        }
        LOG.info(() -> "Kanban: IN_PROGRESS ← " + goal.description()
                + " [" + goal.resourceType() + " wip=" + countInProgress(goal.resourceType())
                + "/" + WIP_LIMITS.get(goal.resourceType()) + "]");
        return goal;
    }

    /**
     * Move a goal from BACKLOG to READY (explicit commit step).
     * Called when the goal meets all pull criteria.
     */
    public void promoteToReady(Goal goal) {
        backlog.remove(goal);
        ready.addLast(goal);
        GoalFlowMetrics m = metrics.get(goal.id());
        if (m != null) {
            m.setPromotedToReady(Instant.now());
        }
        LOG.fine(() -> "Kanban: READY ← " + goal.description());
    }

    /**
     * Promote all backlog goals that meet basic readiness criteria to READY.
     */
    public int promoteReady() {
        int count = 0;
        Iterator<Goal> it = backlog.iterator();
        while (it.hasNext()) {
            Goal g = it.next();
            if (g.active()) {
                it.remove();
                ready.addLast(g);
                GoalFlowMetrics m = metrics.get(g.id());
                if (m != null) {
                    m.setPromotedToReady(Instant.now());
                }
                count++;
            }
        }
        return count;
    }

    /**
     * Mark a goal as completed (IN_PROGRESS → DONE).
     */
    public GoalFlowMetrics complete(UUID goalId) {
        Goal goal = inProgress.remove(goalId);
        if (goal == null) return null;

        if (goal.serviceClass() == Goal.ServiceClass.EXPEDITE) {
            expediteSlotInUse = false;
            totalExpedites++;
        }

        GoalFlowMetrics m = metrics.get(goalId);
        if (m != null) {
            m.setCompleted(Instant.now());
            m.computeLeadTime();
            done.addLast(m);
            while (done.size() > MAX_DONE) done.removeFirst();
        }

        totalCompleted++;
        LOG.info(() -> "Kanban: DONE ← " + (goal != null ? goal.description() : goalId)
                + " (total: " + totalCompleted + ")");
        return m;
    }

    /**
     * Return a failed goal to READY for retry (or BACKLOG if retry count exceeded).
     */
    public void requeue(Goal goal) {
        inProgress.remove(goal.id());
        GoalFlowMetrics m = metrics.get(goal.id());
        if (m != null) {
            m.incrementRetries();
            if (m.getRetries() >= 3) {
                backlog.addFirst(goal);
                LOG.info("Kanban: BACKLOG ← " + goal.description() + " (retry " + m.getRetries() + ")");
                return;
            }
        }
        ready.addFirst(goal);
        LOG.info("Kanban: READY ← " + goal.description() + " (requeued)");
    }

    // ── Query ──────────────────────────────────────────────────

    public int countInProgress(Goal.ResourceType type) {
        return (int) inProgress.values().stream()
                .filter(g -> g.resourceType() == type)
                .count();
    }

    public int totalWip() {
        return inProgress.size();
    }

    public int totalWipPercent() {
        int totalCapacity = WIP_LIMITS.values().stream().mapToInt(Integer::intValue).sum(); // 9
        return totalCapacity == 0 ? 0 : (int) ((double) inProgress.size() / totalCapacity * 100);
    }

    public boolean hasExpedite() {
        return inProgress.values().stream()
                .anyMatch(g -> g.serviceClass() == Goal.ServiceClass.EXPEDITE);
    }

    public int backlogSize() { return backlog.size(); }
    public int readySize() { return ready.size(); }
    public int inProgressSize() { return inProgress.size(); }
    public int doneSize() { return done.size(); }
    public int totalCompleted() { return totalCompleted; }
    public int totalExpedites() { return totalExpedites; }
    public boolean expediteSlotOccupied() { return expediteSlotInUse; }

    public Collection<Goal> inProgressGoals() { return List.copyOf(inProgress.values()); }
    public Collection<Goal> backlogs() { return List.copyOf(backlog); }
    public Collection<Goal> readys() { return List.copyOf(ready); }
    public List<GoalFlowMetrics> recentDone(int limit) {
        var list = new ArrayList<>(done);
        int from = Math.max(0, list.size() - limit);
        return list.subList(from, list.size());
    }

    public GoalFlowMetrics getMetrics(UUID goalId) { return metrics.get(goalId); }
    public List<GoalFlowMetrics> allMetrics() { return List.copyOf(metrics.values()); }

    // ── Snapshot for API ──────────────────────────────────────

    public BoardSnapshot snapshot() {
        return new BoardSnapshot(
                backlogSize(), readySize(), inProgressSize(), doneSize(),
                totalCompleted, totalExpedites, totalWipPercent(),
                List.copyOf(inProgress.values()),
                recentDone(20),
                WIP_LIMITS);
    }

    public record BoardSnapshot(
            int backlogSize, int readySize, int inProgressSize, int doneSize,
            int totalCompleted, int totalExpedites, int wipPercent,
            Collection<Goal> inProgress,
            List<GoalFlowMetrics> recentDone,
            Map<Goal.ResourceType, Integer> wipLimits) {}

    // ── Helpers ───────────────────────────────────────────────

    @FunctionalInterface
    private interface GoalPredicate { boolean test(Goal g); }

    private static Goal findAndRemove(Deque<Goal> deque, GoalPredicate pred) {
        Iterator<Goal> it = deque.iterator();
        while (it.hasNext()) {
            Goal g = it.next();
            if (pred.test(g)) {
                it.remove();
                return g;
            }
        }
        return null;
    }
}
