package de.metis.kernel.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Phase96BridgeTest {

    @Test
    void promotesRunnableOperationalGoalToKanbanBacklog(@TempDir Path tmp) {
        GoalHierarchy h = new GoalHierarchy(tmp.resolve("h.jsonl"));
        KanbanBoard board = new KanbanBoard();
        HorizonKanbanBridge bridge = new HorizonKanbanBridge(h, board);

        // No deadline: must be promoted immediately
        LongHorizonGoal op = new LongHorizonGoal(
                null, "Aktion X durchführen", null,
                GoalHorizon.OPERATIONAL, LongHorizonGoal.Status.ACTIVE,
                null, List.of(), Instant.now(), null, null, null,
                0.0, 70, "metis", List.of());
        h.upsert(op);

        int promoted = bridge.promoteDueGoals();
        assertEquals(1, promoted);
        // Re-running is idempotent
        assertEquals(0, bridge.promoteDueGoals());
        // Tag was set
        assertTrue(h.get(op.id()).orElseThrow().tags().contains("promoted-to-kanban"));
    }

    @Test
    void skipsGoalsWithFutureDeadlineBeyondHorizon(@TempDir Path tmp) {
        GoalHierarchy h = new GoalHierarchy(tmp.resolve("h.jsonl"));
        KanbanBoard board = new KanbanBoard();
        HorizonKanbanBridge bridge = new HorizonKanbanBridge(h, board);

        LongHorizonGoal far = new LongHorizonGoal(
                null, "In 3 Tagen", null,
                GoalHorizon.OPERATIONAL, LongHorizonGoal.Status.ACTIVE,
                null, List.of(), Instant.now(),
                Instant.now().plus(Duration.ofDays(3)),
                null, null, 0.0, 70, "metis", List.of());
        h.upsert(far);
        assertEquals(0, bridge.promoteDueGoals());
    }
}
