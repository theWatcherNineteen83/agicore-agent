package de.metis.modules.evolution;

import de.metis.kernel.world.CausalModel;
import de.metis.kernel.world.HypothesisGenerator;
import de.metis.kernel.world.HypothesisStore;
import de.metis.kernel.world.InterventionRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AutoABTestTest {

    @Test
    void testProcessWithNoPatterns(@TempDir Path dir) {
        var store = new HypothesisStore(dir.resolve("h.jsonl"));
        var gen = new HypothesisGenerator(store);
        var model = new CausalModel();
        var runner = new InterventionRunner(store, model);
        var ab = new AutoABTest(gen, store, runner);

        assertDoesNotThrow(() -> ab.process(java.util.List.of()));
        assertEquals(0, ab.testsRun());
    }

    @Test
    void testProcessGeneratesHypothesis(@TempDir Path dir) {
        var store = new HypothesisStore(dir.resolve("h.jsonl"));
        var gen = new HypothesisGenerator(store);
        var model = new CausalModel();
        var runner = new InterventionRunner(store, model);
        var ab = new AutoABTest(gen, store, runner);

        var pattern = new PatternDetector.Pattern(
                "test_pattern", "A test pattern for validation",
                "kernel/meta/MetaCognition.java", 50);

        ab.process(java.util.List.of(pattern));
        // A hypothesis should have been created and tested
        assertTrue(ab.testsRun() >= 1,
                "Should have at least tested one hypothesis");
    }

    @Test
    void testDeduplicatesRepeatedPatterns(@TempDir Path dir) {
        var store = new HypothesisStore(dir.resolve("h.jsonl"));
        var gen = new HypothesisGenerator(store);
        var model = new CausalModel();
        var runner = new InterventionRunner(store, model);
        var ab = new AutoABTest(gen, store, runner);

        var pattern = new PatternDetector.Pattern(
                "test_pattern_dup", "Dedup check",
                "kernel/meta/MetaCognition.java", 50);

        ab.process(java.util.List.of(pattern));
        int firstRun = ab.testsRun();

        ab.process(java.util.List.of(pattern));
        assertEquals(firstRun, ab.testsRun(),
                "Repeated patterns should not create new tests");
    }

    @Test
    void testMultiplePatterns(@TempDir Path dir) {
        var store = new HypothesisStore(dir.resolve("h.jsonl"));
        var gen = new HypothesisGenerator(store);
        var model = new CausalModel();
        var runner = new InterventionRunner(store, model);
        var ab = new AutoABTest(gen, store, runner);

        var patterns = java.util.List.of(
                new PatternDetector.Pattern("p1", "Pattern 1", "a.java", 30),
                new PatternDetector.Pattern("p2", "Pattern 2", "b.java", 50));

        ab.process(patterns);
        assertEquals(2, ab.testsRun(),
                "Two unique patterns should produce two hypothesis tests");
    }
}
