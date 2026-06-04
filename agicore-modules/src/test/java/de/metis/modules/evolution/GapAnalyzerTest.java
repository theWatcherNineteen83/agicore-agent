package de.metis.modules.evolution;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

class GapAnalyzerTest {

    @Test
    void testAllMetricsOk() {
        var ga = new GapAnalyzer();
        var m = new HashMap<String, Object>();
        m.put("planningEfficiency", 0.8);
        m.put("successRate", 0.9);
        m.put("confidence", 0.7);
        m.put("beliefCount", 200);

        var props = ga.analyze(m);
        assertTrue(props.isEmpty(), "No proposals expected when all metrics are OK");
    }

    @Test
    void testLowPlanning() {
        var ga = new GapAnalyzer();
        var m = new HashMap<String, Object>();
        m.put("planningEfficiency", 0.2);
        m.put("successRate", 0.9);
        m.put("confidence", 0.7);
        m.put("beliefCount", 200);

        var props = ga.analyze(m);
        assertTrue(props.stream().anyMatch(p -> p.id().equals("improve_planning_efficiency")));
    }

    @Test
    void testLowSuccessRate() {
        var ga = new GapAnalyzer();
        var m = new HashMap<String, Object>();
        m.put("planningEfficiency", 0.8);
        m.put("successRate", 0.3);
        m.put("confidence", 0.7);
        m.put("beliefCount", 200);

        var props = ga.analyze(m);
        assertTrue(props.stream().anyMatch(p -> p.id().equals("improve_goal_success_rate")));
    }

    @Test
    void testCooldownSuppressesDuplicates() {
        var ga = new GapAnalyzer();
        var m = new HashMap<String, Object>();
        m.put("planningEfficiency", 0.2);
        m.put("successRate", 0.9);
        m.put("confidence", 0.7);
        m.put("beliefCount", 200);

        var first = ga.analyze(m);
        assertFalse(first.isEmpty());

        var second = ga.analyze(m);
        assertTrue(second.stream().noneMatch(p -> p.id().equals("improve_planning_efficiency")),
                "Same proposal should be on cooldown");
    }
}
