package de.metis.kernel.goal;

import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Phase 9.6b — Brücke Long-Horizon → Kanban-Board.
 *
 * <p>Wenn ein {@link LongHorizonGoal} mit Horizon {@link GoalHorizon#OPERATIONAL}
 * runnable und fällig ist (deadline ≤ jetzt+24h, status PROPOSED oder ACTIVE),
 * erzeugen wir ein klassisches Tick-Ebenen-{@link Goal} und legen es ins
 * {@link KanbanBoard}-BACKLOG.
 *
 * <p>Idempotent: pro LongHorizonGoal wird höchstens einmal ein Kanban-Goal
 * angelegt; wir tracken das in {@code promoted} (UUID-Set in-Memory). Die
 * eigentliche „Promotion erfolgt"-Persistenz machen wir über
 * {@code LongHorizonGoal.tags += "promoted-to-kanban"}.
 *
 * <p>Wird vom AgentMain im 5-Min-Takt aufgerufen.
 */
public class HorizonKanbanBridge {

    private static final Logger LOG = Logger.getLogger(HorizonKanbanBridge.class.getName());
    private static final String PROMOTED_TAG = "promoted-to-kanban";

    private final GoalHierarchy hierarchy;
    private final KanbanBoard board;
    private final Set<UUID> promotedInSession = new HashSet<>();

    public HorizonKanbanBridge(GoalHierarchy hierarchy, KanbanBoard board) {
        this.hierarchy = hierarchy;
        this.board = board;
    }

    /**
     * One promotion pass.
     *
     * @return number of LongHorizonGoals newly promoted to the Kanban backlog
     */
    public synchronized int promoteDueGoals() {
        if (board == null) return 0;
        int promoted = 0;
        Instant horizon = Instant.now().plusSeconds(24L * 3600);
        for (LongHorizonGoal g : hierarchy.openByHorizon(GoalHorizon.OPERATIONAL)) {
            if (g.tags().contains(PROMOTED_TAG)) continue;
            if (promotedInSession.contains(g.id())) continue;
            if (!hierarchy.isRunnable(g.id())) continue;
            // Promote when no deadline (always) OR deadline within 24h
            if (g.deadline() != null && g.deadline().isAfter(horizon)) continue;

            Goal kg = buildKanbanGoal(g);
            try {
                board.add(kg);
                List<String> nextTags = new ArrayList<>(g.tags());
                nextTags.add(PROMOTED_TAG);
                hierarchy.upsert(new LongHorizonGoal(
                        g.id(), g.title(), g.rationale(), g.horizon(),
                        LongHorizonGoal.Status.ACTIVE,
                        g.parentId(), g.childIds(),
                        g.createdAt(), g.deadline(),
                        Instant.now(), g.completedAt(),
                        Math.max(g.progress(), 0.05),
                        g.priority(), g.owner(),
                        List.copyOf(nextTags)
                ));
                promotedInSession.add(g.id());
                promoted++;
                LOG.info("HorizonKanbanBridge: promoted '" + g.title()
                        + "' (id=" + g.id() + ") -> BACKLOG");
            } catch (Exception e) {
                LOG.warning("HorizonKanbanBridge: promotion failed for " + g.id() + ": " + e.getMessage());
            }
        }
        return promoted;
    }

    private Goal buildKanbanGoal(LongHorizonGoal lhg) {
        Goal.ServiceClass svc = lhg.deadline() != null
                ? Goal.ServiceClass.FIXED_DATE
                : Goal.ServiceClass.STANDARD;
        Goal.ResourceType rt = inferResourceType(lhg);
        return new Goal(
                lhg.title(),
                "long-horizon",
                Math.max(1, Math.min(100, lhg.priority())),
                Math.max(0.5, Math.min(0.95, 0.5 + lhg.progress() * 0.5)),
                1,
                svc,
                rt,
                lhg.deadline()
        );
    }

    private Goal.ResourceType inferResourceType(LongHorizonGoal g) {
        String s = (g.title() + " " + String.join(" ", g.tags())).toLowerCase();
        if (s.contains("vision") || s.contains("camera") || s.contains("gpu")) return Goal.ResourceType.GPU_HEAVY;
        if (s.contains("compile") || s.contains("javac") || s.contains("speech")
                || s.contains("tts") || s.contains("stt")) return Goal.ResourceType.CPU_HEAVY;
        if (s.contains("plan") || s.contains("inference") || s.contains("chat")
                || s.contains("llm") || s.contains("ollama") || s.contains("mutation")) return Goal.ResourceType.INFERENCE;
        return Goal.ResourceType.LIGHT;
    }
}
