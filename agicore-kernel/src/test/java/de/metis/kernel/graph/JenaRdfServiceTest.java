package de.metis.kernel.graph;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JenaRdfService — graph-based knowledge store.
 */
class JenaRdfServiceTest {

    private JenaRdfService service;
    private java.nio.file.Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        // Use temp directory for TDB2 to avoid cross-test pollution
        tempDir = java.nio.file.Files.createTempDirectory("jena-test-");
        System.setProperty("metis.jena.dir", tempDir.toString());
        System.setProperty("metis.jena.enabled", "true");
        service = JenaRdfService.getInstance();
        service.init();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        System.clearProperty("metis.jena.dir");
        System.clearProperty("metis.jena.enabled");
        // Clean up temp dir
        try {
            java.nio.file.Files.walk(tempDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {}
    }

    @Test
    void serviceIsSingleton() {
        var s2 = JenaRdfService.getInstance();
        assertSame(service, s2);
    }

    @Test
    void storeAndQueryBelief() {
        service.storeBelief("test-1", "relatedTo", "Java 25", 0.85, "wiki");

        var results = service.queryBeliefs("relatedTo", 10);
        assertEquals(1, results.size());
        assertEquals("test-1", results.get(0).get("subject"));
        assertEquals("Java 25", results.get(0).get("object"));
    }

    @Test
    void storeAndQueryCausal() {
        service.storeCausalLink("memoryPressure", "slowdown", 0.92, "watchdog-obs");

        var causes = service.queryCauses("slowdown");
        assertTrue(causes.get(0).get("cause").contains("memoryPressure"),
                "Expected memoryPressure in cause, got: " + causes.get(0));

        var effects = service.queryEffects("memoryPressure");
        assertTrue(effects.size() > 0, "Should find at least one effect");
    }

    @Test
    void causalClosure() {
        // Build chain: A -> B -> C
        service.storeCausalLink("A", "B", 0.9, "test");
        service.storeCausalLink("B", "C", 0.8, "test");

        var closure = service.causalClosure("A", 3);
        assertTrue(closure.contains(JenaRdfService.NS + "variable/C"),
                "Transitive closure should contain C");
    }

    @Test
    void tripleCountIncreases() {
        long before = service.tripleCount();
        service.storeBelief("counter-test", "observes", "CPU_95pct", 1.0, "hardware");
        long after = service.tripleCount();
        assertTrue(after > before,
                "Triple count should increase: " + before + " -> " + after);
    }

    @Test
    void nullResultsWhenNotInitialized() {
        service.shutdown();
        var result = service.storeBelief("late", "test", "val", 1.0, "test");
        assertNull(result, "Store should return null when not initialized");
    }
}
