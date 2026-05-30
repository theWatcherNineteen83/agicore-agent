package de.metis.kernel.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Phase9LongHorizonTest {

    @Test
    void horizonDecomposeChain() {
        assertTrue(GoalHorizon.STRATEGIC.canBeDecomposed());
        assertEquals(GoalHorizon.TACTICAL, GoalHorizon.STRATEGIC.nextDown());
        assertEquals(GoalHorizon.OPERATIONAL, GoalHorizon.TACTICAL.nextDown());
        assertEquals(GoalHorizon.TICK, GoalHorizon.OPERATIONAL.nextDown());
        assertNull(GoalHorizon.TICK.nextDown());
        assertFalse(GoalHorizon.LIFETIME.canBeDecomposed());
    }

    @Test
    void longHorizonGoalEnforcesInvariants() {
        LongHorizonGoal g = new LongHorizonGoal(
                null, "Lerne Phase 9", "rationale",
                GoalHorizon.STRATEGIC, null,
                null, null, null,
                Instant.now().plusSeconds(60),
                null, null, -0.5, 200, null, null);
        assertNotNull(g.id());
        assertEquals(LongHorizonGoal.Status.PROPOSED, g.status());
        assertEquals(0.0, g.progress());
        assertEquals(100, g.priority());  // clamped
        assertEquals("metis", g.owner());
        assertTrue(g.isOpen());

        assertThrows(IllegalArgumentException.class, () -> new LongHorizonGoal(
                null, "", "x", GoalHorizon.STRATEGIC, null, null, null, null,
                null, null, null, 0.0, 50, null, null));
    }

    @Test
    void hierarchyAppendsAndReloads(@TempDir Path tmp) {
        Path file = tmp.resolve("hier.jsonl");
        GoalHierarchy h = new GoalHierarchy(file);
        LongHorizonGoal a = new LongHorizonGoal(
                null, "S1", null, GoalHorizon.STRATEGIC, null,
                null, null, null, null, null, null, 0.0, 50, null, null);
        h.upsert(a);
        assertEquals(1, h.size());
        GoalHierarchy reload = new GoalHierarchy(file);
        assertEquals(1, reload.size());
        assertTrue(reload.get(a.id()).isPresent());
    }

    @Test
    void horizonPlannerDeterministicDecompose(@TempDir Path tmp) {
        GoalHierarchy h = new GoalHierarchy(tmp.resolve("h.jsonl"));
        HorizonPlanner p = new HorizonPlanner(h);
        LongHorizonGoal s = p.proposeStrategic("Phase 9 implementieren", "EDI näher", 90, List.of("phase9"));
        List<LongHorizonGoal> tac = p.decompose(s);
        assertEquals(3, tac.size());
        assertEquals(GoalHorizon.TACTICAL, tac.get(0).horizon());
        // parent gets child IDs
        LongHorizonGoal sReloaded = h.get(s.id()).orElseThrow();
        assertEquals(3, sReloaded.childIds().size());
        // Idempotent: second decompose returns empty
        assertTrue(p.decompose(sReloaded).isEmpty());
    }

    @Test
    void commitmentRegisterTracksPromises(@TempDir Path tmp) {
        GoalHierarchy h = new GoalHierarchy(tmp.resolve("h.jsonl"));
        CommitmentRegister cr = new CommitmentRegister(h);
        Instant due = Instant.now().plus(Duration.ofHours(2));
        LongHorizonGoal g = cr.record("Georg", "ich melde mich nach Phase 9", due);
        assertNotNull(g);
        assertTrue(g.tags().contains("commitment"));
        assertEquals("Georg", g.owner());
        assertEquals(1, cr.openCommitments().size());
        assertEquals(1, cr.openFor("Georg").size());
        assertEquals(0, cr.overdue().size());
        cr.markDone(g.id());
        assertEquals(0, cr.openCommitments().size());
    }

    @Test
    void revisionAutoCompletesAndBlocksOverdue(@TempDir Path tmp) {
        GoalHierarchy h = new GoalHierarchy(tmp.resolve("h.jsonl"));
        // 1 goal with progress 1.0 but ACTIVE status
        LongHorizonGoal almost = new LongHorizonGoal(
                null, "almost", null, GoalHorizon.OPERATIONAL,
                LongHorizonGoal.Status.ACTIVE, null, null, null, null, null, null,
                1.0, 60, null, null);
        h.upsert(almost);
        // 1 goal overdue
        LongHorizonGoal late = new LongHorizonGoal(
                null, "late", null, GoalHorizon.TACTICAL,
                LongHorizonGoal.Status.ACTIVE, null, null, null,
                Instant.now().minusSeconds(60), null, null,
                0.3, 70, null, null);
        h.upsert(late);

        GoalRevisionEngine eng = new GoalRevisionEngine(h);
        var report = eng.revise();
        assertTrue(report.anyChange());
        assertEquals(1, report.autoCompleted());
        assertEquals(1, report.overdue());

        assertEquals(LongHorizonGoal.Status.DONE,
                h.get(almost.id()).orElseThrow().status());
        assertEquals(LongHorizonGoal.Status.BLOCKED,
                h.get(late.id()).orElseThrow().status());
    }

    @Test
    void parentProgressRollupFromChildren(@TempDir Path tmp) {
        GoalHierarchy h = new GoalHierarchy(tmp.resolve("h.jsonl"));
        HorizonPlanner p = new HorizonPlanner(h);
        LongHorizonGoal parent = p.proposeStrategic("P", "r", 80, List.of());
        var kids = p.decompose(parent);
        // mark first child DONE
        h.upsert(kids.get(0).withStatus(LongHorizonGoal.Status.DONE));
        // others 50%
        h.upsert(kids.get(1).withProgress(0.5));
        h.upsert(kids.get(2).withProgress(0.5));

        h.rollupProgress(parent.id());
        double p2 = h.get(parent.id()).orElseThrow().progress();
        // mean of (1.0, 0.5, 0.5) = 0.666...
        assertEquals(0.666, p2, 0.01);
    }
}
