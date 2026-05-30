package de.metis.kernel.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class Phase10CausalTest {

    @Test
    void hypothesisRecordEnforcesInvariants() {
        CausalHypothesis h = new CausalHypothesis(
                null, "cpu_high", "load>0.9", "latency_up",
                CausalHypothesis.Direction.UP, 1.5, null, null,
                null, null, null, null, 0.0, null);
        assertEquals(CausalHypothesis.Status.PROPOSED, h.status());
        assertEquals(1.0, h.predictedMagnitude());
        assertTrue(h.isOpen());

        assertThrows(IllegalArgumentException.class,
                () -> new CausalHypothesis(null, "", "c", "e",
                        null, 0.5, null, null, null, null, null, null, 0.0, null));
    }

    @Test
    void storePersistsAcrossReload(@TempDir Path tmp) {
        Path file = tmp.resolve("hyp.jsonl");
        HypothesisStore s = new HypothesisStore(file);
        HypothesisGenerator g = new HypothesisGenerator(s);
        var h = g.propose("Wikipedia-Lerntick", "off-hours",
                "beliefCount steigt", "Test");
        assertNotNull(h);
        assertEquals(1, s.size());
        HypothesisStore reload = new HypothesisStore(file);
        assertEquals(1, reload.size());
        assertTrue(reload.get(h.id()).isPresent());
    }

    @Test
    void generatorDeduplicatesOnSameTriple(@TempDir Path tmp) {
        HypothesisStore s = new HypothesisStore(tmp.resolve("h.jsonl"));
        HypothesisGenerator g = new HypothesisGenerator(s);
        var h1 = g.propose("cpu_load_high", "evening", "latency_increase", "r1");
        var h2 = g.propose("cpu_load_high", "evening", "latency_increase", "r2");
        assertEquals(h1.id(), h2.id());
        assertEquals(1, s.size());
    }

    @Test
    void interventionRunnerConcludesUpAsConfirmed(@TempDir Path tmp) {
        HypothesisStore s = new HypothesisStore(tmp.resolve("h.jsonl"));
        HypothesisGenerator g = new HypothesisGenerator(s);
        CausalModel cm = new CausalModel();
        InterventionRunner ir = new InterventionRunner(s, cm);

        var hyp = g.propose("keep_alive=10m setzen", "vor LLM-Burst",
                "Latenz sinkt", "Hot-Reload-Hypothese");
        var testing = ir.startTesting(hyp);
        assertEquals(CausalHypothesis.Status.TESTING, testing.status());
        // pred=UP, observed delta +0.3 -> UP -> CONFIRMED
        var done = ir.conclude(testing, 1.0, 1.3);
        assertEquals(CausalHypothesis.Status.CONFIRMED, done.status());
        assertEquals(CausalHypothesis.Direction.UP, done.observedDirection());
        assertTrue(cm.size() >= 1);
    }

    @Test
    void interventionRunnerRefutesOnWrongDirection(@TempDir Path tmp) {
        HypothesisStore s = new HypothesisStore(tmp.resolve("h.jsonl"));
        HypothesisGenerator g = new HypothesisGenerator(s);
        CausalModel cm = new CausalModel();
        InterventionRunner ir = new InterventionRunner(s, cm);

        var hyp = g.propose("mehr Cache", "small workload",
                "Latenz sinkt", "Cache-Hypothese");  // direction=UP per heuristik
        var testing = ir.startTesting(hyp);
        // delta -0.5 -> DOWN -> REFUTED (predicted UP)
        var done = ir.conclude(testing, 1.0, 0.5);
        assertEquals(CausalHypothesis.Status.REFUTED, done.status());
        assertEquals(CausalHypothesis.Direction.DOWN, done.observedDirection());
    }

    @Test
    void runSyncRunsInterventionAndConcludes(@TempDir Path tmp) {
        HypothesisStore s = new HypothesisStore(tmp.resolve("h.jsonl"));
        InterventionRunner ir = new InterventionRunner(s, new CausalModel());
        CausalHypothesis h = new CausalHypothesis(null, "c", "cond", "e",
                CausalHypothesis.Direction.UP, 0.6, null, null,
                null, null, null, null, 0.0, null);
        s.upsert(h);

        double[] value = {0.0};
        var done = ir.runSync(h, () -> value[0], () -> value[0] += 0.5);
        assertEquals(CausalHypothesis.Status.CONFIRMED, done.status());
    }

    @Test
    void counterfactualReportsZeroEvidenceCleanly() {
        Counterfactual cf = new Counterfactual(new CausalModel());
        var ans = cf.query("Wiki-Burst-Tick", "off-hours", "VRAM-Spitze");
        assertEquals(0, ans.evidence());
        assertTrue(ans.explanation().contains("Spekulation"));
    }

    @Test
    void counterfactualUsesCausalEvidence() {
        CausalModel cm = new CausalModel();
        for (int i = 0; i < 5; i++) {
            cm.observe("LLM-Inferenz", "kein keep_alive", "Cold-Start-Spike", true);
        }
        Counterfactual cf = new Counterfactual(cm);
        var ans = cf.query("LLM-Inferenz", "kein keep_alive", "Cold-Start-Spike");
        assertTrue(ans.evidence() >= 5);
        assertTrue(ans.confidence() > 0.5);
    }
}
