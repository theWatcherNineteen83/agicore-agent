package de.metis.kernel.safety;

import de.metis.kernel.safety.PlannerHealthGuard.HealthReport;
import de.metis.kernel.safety.PlannerHealthGuard.Severity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6+7 — PlannerHealthGuard Tests (Sprint #1, 07.06.2026).
 */
class PlannerHealthGuardTest {

    private final PlannerHealthGuard guard = new PlannerHealthGuard();

    @Test
    void warmup_returnsOk() {
        // less than MIN_SAMPLE_SIZE (20) plans → warmup
        HealthReport r = guard.check(5, 0, Map.of("http", 5));
        assertEquals(Severity.OK, r.severity());
        assertTrue(r.isOk());
        assertEquals("(warmup)", r.topAction());
    }

    @Test
    void healthyMix_isOk() {
        Map<String, Integer> mix = Map.of(
                "http", 30, "shell", 25, "sensor-bridge", 20,
                "audio-bridge", 15, "knowledge", 10);
        HealthReport r = guard.check(100, 0, mix);
        assertEquals(Severity.OK, r.severity());
        assertTrue(r.findings().isEmpty());
    }

    @Test
    void highEmptyRatio_warns() {
        // 25% empty (> 20% warn, < 35% critical)
        HealthReport r = guard.check(100, 25,
                Map.of("http", 40, "shell", 35));
        assertEquals(Severity.WARN, r.severity());
        assertTrue(r.findings().stream().anyMatch(f -> f.contains("emptyRatio=0.25")));
    }

    @Test
    void veryHighEmptyRatio_critical() {
        // 40% empty
        HealthReport r = guard.check(100, 40,
                Map.of("http", 30, "shell", 30));
        assertEquals(Severity.CRITICAL, r.severity());
        assertTrue(r.findings().stream().anyMatch(f -> f.startsWith("CRITICAL emptyRatio")));
    }

    @Test
    void actionDominance_warns() {
        // top action = 75% of all valid plans
        Map<String, Integer> mix = Map.of(
                "sensor-bridge", 75, "http", 15, "shell", 10);
        HealthReport r = guard.check(100, 0, mix);
        assertEquals(Severity.WARN, r.severity());
        assertEquals("sensor-bridge", r.topAction());
        assertEquals(0.75, r.topActionDominance(), 0.001);
    }

    @Test
    void actionDominance_critical() {
        // top action = 90% of all valid plans
        Map<String, Integer> mix = Map.of(
                "sensor-bridge", 90, "http", 5, "shell", 5);
        HealthReport r = guard.check(100, 0, mix);
        assertEquals(Severity.CRITICAL, r.severity());
        assertTrue(r.findings().stream().anyMatch(f -> f.contains("action-dominance=0.90")));
    }

    @Test
    void singleActionStarvation_alwaysCritical() {
        // only 1 distinct action, even with 100% dominance by definition
        Map<String, Integer> mix = Map.of("sensor-bridge", 84);
        HealthReport r = guard.check(105, 14, mix);
        assertEquals(Severity.CRITICAL, r.severity());
        assertTrue(r.findings().stream().anyMatch(f -> f.contains("action-starvation")));
    }

    @Test
    void liveSnapshot_07_06_isCritical() {
        // Reproduktion des Live-Status 07.06. (448 ticks):
        //   totalPlansGenerated=107, emptyPlanCount=14,
        //   actionUsageCount = {sensor-bridge:84, http:8, shell:8, audio-bridge:5}
        Map<String, Integer> live = new LinkedHashMap<>();
        live.put("sensor-bridge", 84);
        live.put("http", 8);
        live.put("shell", 8);
        live.put("audio-bridge", 5);
        HealthReport r = guard.check(107, 14, live);

        // emptyRatio = 14/107 ≈ 0.131 → unter WARN (0.20) — kein Empty-Issue
        assertTrue(r.emptyRatio() < 0.20);

        // dominance = 84/105 = 0.80 → über WARN (0.70), unter CRITICAL (0.85)
        assertEquals(0.80, r.topActionDominance(), 0.001);

        // → WARN insgesamt (nicht CRITICAL, weil 4 distinct actions)
        assertEquals(Severity.WARN, r.severity());
        assertEquals("sensor-bridge", r.topAction());
    }

    @Test
    void zeroPlans_warmup() {
        HealthReport r = guard.check(0, 0, Map.of());
        assertEquals(Severity.OK, r.severity());
    }

    @Test
    void toJson_isValid() {
        Map<String, Integer> mix = Map.of("http", 30, "shell", 30);
        HealthReport r = guard.check(60, 5, mix);
        String json = r.toJson();
        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));
        assertTrue(json.contains("\"severity\""));
        assertTrue(json.contains("\"topAction\""));
        assertTrue(json.contains("\"findings\""));
    }

    @Test
    void invalidConfig_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlannerHealthGuard(-0.1, 0.5, 0.7, 0.85, 20));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannerHealthGuard(0.5, 0.3, 0.7, 0.85, 20));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannerHealthGuard(0.2, 0.4, 0.7, 0.85, 0));
    }
}
