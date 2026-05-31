package de.metis.kernel.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 10.7 — Safety constraints for active causal hypotheses:
 * do-operator whitelist, max 1 intervention/tick, max 10 TESTING.
 */
class CausalSafetyGateTest {

    private static CausalHypothesis sample(String cause) {
        // Constructor: UUID, cause, condition, effect, predictedDirection,
        // priorConfidence, source, note, status, createdAt, testedAt,
        // observedDirection, observedMagnitude, resultNote
        return new CausalHypothesis(
                null,
                cause, null, "effect",
                CausalHypothesis.Direction.UP,
                0.5,
                "test", null,
                CausalHypothesis.Status.PROPOSED,
                java.time.Instant.now(), null,
                null, 0.0, null);
    }

    @Test
    void emptyWhitelistInLenientModeAllowsAll() {
        CausalSafetyGate gate = new CausalSafetyGate()
                .setMaxInterventionsPerTick(10);
        HypothesisStore store = new HypothesisStore(java.nio.file.Path.of("/tmp/h-empty-lenient.jsonl"));

        // empty whitelist + strict=false (default) ⇒ pass-through
        assertTrue(gate.tryStart(sample("anything"), store).allowed());
    }

    @Test
    void emptyWhitelistInStrictModeRejectsAll() {
        CausalSafetyGate gate = new CausalSafetyGate()
                .setStrict(true);
        HypothesisStore store = new HypothesisStore(java.nio.file.Path.of("/tmp/h-empty-strict.jsonl"));

        CausalSafetyGate.Decision d = gate.tryStart(sample("anything"), store);
        assertFalse(d.allowed());
        assertEquals(CausalSafetyGate.Outcome.REJECTED_WHITELIST, d.outcome());
    }

    @Test
    void nonEmptyWhitelistEnforcedRegardlessOfStrictFlag() {
        CausalSafetyGate gate = new CausalSafetyGate()
                .allow("temperature")
                .setMaxInterventionsPerTick(5);
        HypothesisStore store = new HypothesisStore(java.nio.file.Path.of("/tmp/h-wl.jsonl"));

        assertTrue(gate.tryStart(sample("temperature"), store).allowed());

        CausalSafetyGate.Decision d = gate.tryStart(sample("humidity"), store);
        assertFalse(d.allowed());
        assertEquals(CausalSafetyGate.Outcome.REJECTED_WHITELIST, d.outcome());
    }

    @Test
    void tickBudgetEnforcedAndResetOnTick() {
        CausalSafetyGate gate = new CausalSafetyGate()
                .allow("c")
                .setMaxInterventionsPerTick(1);
        HypothesisStore store = new HypothesisStore(java.nio.file.Path.of("/tmp/h-tick.jsonl"));

        assertTrue(gate.tryStart(sample("c"), store).allowed());

        CausalSafetyGate.Decision d = gate.tryStart(sample("c"), store);
        assertFalse(d.allowed());
        assertEquals(CausalSafetyGate.Outcome.REJECTED_TICK_BUDGET, d.outcome());

        gate.onTick();
        assertTrue(gate.tryStart(sample("c"), store).allowed(),
                "after onTick(), budget must reset");
    }

    @Test
    void capacityLimitOnTestingPopulation(@TempDir Path tmp) {
        // Use a real HypothesisStore so we can count TESTING rows.
        HypothesisStore store = new HypothesisStore(tmp.resolve("h.jsonl"));
        CausalSafetyGate gate = new CausalSafetyGate()
                .allow("c")
                .setMaxInterventionsPerTick(99)
                .setMaxConcurrentTesting(2);

        // Pre-populate two TESTING hypotheses directly in the store
        for (int i = 0; i < 2; i++) {
            CausalHypothesis h = sample("c").withStatus(CausalHypothesis.Status.TESTING);
            store.upsert(h);
        }

        CausalSafetyGate.Decision d = gate.tryStart(sample("c"), store);
        assertFalse(d.allowed());
        assertEquals(CausalSafetyGate.Outcome.REJECTED_TESTING_CAPACITY, d.outcome());
    }

    @Test
    void interventionRunnerHonoursGate(@TempDir Path tmp) {
        HypothesisStore store = new HypothesisStore(tmp.resolve("h.jsonl"));
        CausalModel causal = new CausalModel();
        InterventionRunner runner = new InterventionRunner(store, causal);

        CausalSafetyGate gate = new CausalSafetyGate()
                .setStrict(true)              // deny-by-default
                .setMaxInterventionsPerTick(1);
        runner.setSafetyGate(gate);

        CausalHypothesis h = sample("forbidden_cause");
        CausalHypothesis testing = runner.startTesting(h);

        assertNull(testing, "strict deny-by-default must block startTesting");
        assertEquals(1, runner.safetyRejections());
    }

    @Test
    void runSyncReturnsNullWhenBlocked(@TempDir Path tmp) {
        HypothesisStore store = new HypothesisStore(tmp.resolve("h.jsonl"));
        InterventionRunner runner = new InterventionRunner(store, new CausalModel());
        runner.setSafetyGate(new CausalSafetyGate().setStrict(true));

        java.util.concurrent.atomic.AtomicBoolean interventionRan = new java.util.concurrent.atomic.AtomicBoolean();
        CausalHypothesis result = runner.runSync(
                sample("anything"),
                () -> 1.0,
                () -> interventionRan.set(true));

        assertNull(result);
        assertFalse(interventionRan.get(),
                "blocked intervention must not be executed");
    }

    @Test
    void decisionCountersReflectOutcomes() {
        CausalSafetyGate gate = new CausalSafetyGate()
                .allow("c")
                .setMaxInterventionsPerTick(1);
        HypothesisStore store = new HypothesisStore(java.nio.file.Path.of("/tmp/h-counters.jsonl"));

        gate.tryStart(sample("c"), store);                  // ALLOWED
        gate.tryStart(sample("x"), store);                  // REJECTED_WHITELIST
        gate.tryStart(sample("c"), store);                  // REJECTED_TICK_BUDGET

        assertEquals(1, gate.countOf(CausalSafetyGate.Outcome.ALLOWED));
        assertEquals(1, gate.countOf(CausalSafetyGate.Outcome.REJECTED_WHITELIST));
        assertEquals(1, gate.countOf(CausalSafetyGate.Outcome.REJECTED_TICK_BUDGET));
    }
}
