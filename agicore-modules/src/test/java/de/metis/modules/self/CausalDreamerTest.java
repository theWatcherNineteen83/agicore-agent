package de.metis.modules.self;

import de.metis.kernel.goal.KanbanBoard;
import de.metis.kernel.memory.Experience;
import de.metis.kernel.memory.MemoryConsolidator;
import de.metis.kernel.memory.ShortTermMemory;
import de.metis.kernel.memory.LongTermMemory;
import de.metis.kernel.self.SelfNarrative;
import de.metis.kernel.world.CausalModel;
import de.metis.kernel.world.InterventionRunner;

import de.metis.kernel.world.HypothesisGenerator;
import de.metis.kernel.world.HypothesisStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 10.5 — CausalDreamer.
 */
class CausalDreamerTest {

    private static MemoryConsolidator mem() {
        var stm = new ShortTermMemory(10);
        return new MemoryConsolidator(stm, new LongTermMemory());
    }

    @Test
    void noDreamWhenWipHigh(@TempDir Path dir) {
        var stm = new ShortTermMemory(10);
        stm.add(new Experience("goal1", "shell", true, "ok", 0.1, new double[0]));
        var m = new MemoryConsolidator(stm, new LongTermMemory());
        var kanban = new KanbanBoard();
        // add → BACKLOG, promoteToReady → READY, pull → IN_PROGRESS
        for (int i = 0; i < 3; i++) {
            var g = new de.metis.kernel.goal.Goal("task" + i, "test", 50, 0.5, 1);
            kanban.add(g);
            kanban.promoteToReady(g);
        }
        kanban.pull(); kanban.pull(); kanban.pull(); // WIP=3
        var hs = new HypothesisStore(dir.resolve("hypotheses.jsonl"));
        var hg = new HypothesisGenerator(hs);
        var sn = new SelfNarrative(dir.resolve("n.md"));
        var dreamer = new CausalDreamer(
                m, kanban, hg, hs, sn, new InterventionRunner(hs, new CausalModel()), 10, 10);
        assertFalse(dreamer.dreamOnce(), "WIP ≥ 2 → kein Traum");
    }

    @Test
    void createsHypothesisWhenIdle(@TempDir Path dir) {
        var stm = new ShortTermMemory(10);
        stm.add(new Experience("System-Check", "shell", true, "all good", 0.1, new double[0]));
        var m = new MemoryConsolidator(stm, new LongTermMemory());
        var kanban = new KanbanBoard(); // empty → WIP=0
        var hs = new HypothesisStore(dir.resolve("hypotheses.jsonl"));
        var hg = new HypothesisGenerator(hs);
        var sn = new SelfNarrative(dir.resolve("n.md"));
        var dreamer = new CausalDreamer(
                m, kanban, hg, hs, sn, new InterventionRunner(hs, new CausalModel()), 10, 10);
        assertTrue(dreamer.dreamOnce());
        assertEquals(1, dreamer.hypothesesCreated());
        assertEquals(1, hs.all().size()); // 1 hypothesis (TESTING after intervention)
    }

    @Test
    void overflowProtectionRespected(@TempDir Path dir) {
        var stm = new ShortTermMemory(10);
        stm.add(new Experience("task", "http", true, "done", 0.1, new double[0]));
        var m = new MemoryConsolidator(stm, new LongTermMemory());
        var kanban = new KanbanBoard();
        var hs = new HypothesisStore(dir.resolve("hypotheses.jsonl"));
        var hg = new HypothesisGenerator(hs);
        var sn = new SelfNarrative(dir.resolve("n.md"));
        for (int i = 0; i < 5; i++) {
            hs.upsert(hg.propose("cause" + i, "cond", "effect", "rationale"));
        }
        assertEquals(5, hs.open().size(), "pre-fill: 5 open hypotheses");
        var dreamer = new CausalDreamer(
                m, kanban, hg, hs, sn, new InterventionRunner(hs, new CausalModel()), 10, 4);
        assertFalse(dreamer.dreamOnce(), "overflow: 5 >= 4 → skip");
        assertEquals(5, hs.open().size(), "no new hypothesis added");
    }

    @Test
    void narrativeAppendedOnDream(@TempDir Path dir) {
        var stm = new ShortTermMemory(10);
        stm.add(new Experience("Goal: check memory", "memory-query",
                true, "RAM: 64148 MB", 0.05, new double[0]));
        var m = new MemoryConsolidator(stm, new LongTermMemory());
        var kanban = new KanbanBoard();
        var hs = new HypothesisStore(dir.resolve("hypotheses.jsonl"));
        var hg = new HypothesisGenerator(hs);
        var sn = new SelfNarrative(dir.resolve("n.md"));
        var dreamer = new CausalDreamer(
                m, kanban, hg, hs, sn, new InterventionRunner(hs, new CausalModel()), 10, 10);
        assertTrue(dreamer.dreamOnce());
        assertTrue(sn.recentContext().contains("CausalDream"));
    }

    @Test
    void noDreamWithEmptyMemory(@TempDir Path dir) {
        var m = mem();
        var kanban = new KanbanBoard();
        var hs = new HypothesisStore(dir.resolve("hypotheses.jsonl"));
        var hg = new HypothesisGenerator(hs);
        var sn = new SelfNarrative(dir.resolve("n.md"));
        var dreamer = new CausalDreamer(
                m, kanban, hg, hs, sn, new InterventionRunner(hs, new CausalModel()), 10, 10);
        assertFalse(dreamer.dreamOnce(), "leeres Gedächtnis → kein Traum");
    }
}
