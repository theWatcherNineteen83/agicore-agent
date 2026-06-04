package de.metis.kernel.self;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 12a BugTracker auto-revert and fix exhaustion.
 */
class AutoRevertTest {

    @Test
    void testRollbackTriggerOnExhaustion() {
        var tracker = new BugTracker();
        final boolean[] rolledBack = {false};
        tracker.withRollbackTrigger(() -> rolledBack[0] = true);

        // Report same bug 3+ times → triggers rollback
        RuntimeException ex = new RuntimeException("exhaust-test");
        for (int i = 0; i < 4; i++) {
            boolean result = tracker.report("tick", ex);
            // Keep going even after cooldown to exhaust MAX_FIX_ATTEMPTS
        }
        assertTrue(rolledBack[0], "Rollback should trigger after fix exhaustion");
    }

    @Test
    void testFixGoalTriggerOnFirstBug() {
        var tracker = new BugTracker();
        final String[] triggered = {null};
        tracker.withFixGoalTrigger(desc -> triggered[0] = desc);

        boolean result = tracker.report("planner", new RuntimeException("fix-needed"));
        assertTrue(result, "First bug should trigger");
        assertNotNull(triggered[0], "Fix goal callback should be called");
        assertTrue(triggered[0].contains("fix-needed"));
    }

    @Test
    void testNoRollbackOnUniqueBug() {
        var tracker = new BugTracker();
        final boolean[] rolledBack = {false};
        tracker.withRollbackTrigger(() -> rolledBack[0] = true);

        // Different bugs each time → no exhaustion
        for (int i = 0; i < 5; i++) {
            tracker.report("tick-" + i, new RuntimeException("bug-" + i));
        }
        assertFalse(rolledBack[0], "Different bugs should not trigger rollback");
        assertEquals(5, tracker.size());
    }

    @Test
    void testOpenCountAfterExhaustion() {
        var tracker = new BugTracker();
        RuntimeException ex = new RuntimeException("exhaust");

        // 4 reports needed to exhaust (1 new + 3 cooldown-increments)
        tracker.report("tick", ex);
        assertEquals(1, tracker.openCount(), "Bug open before exhaustion");

        tracker.report("tick", ex);
        tracker.report("tick", ex);
        tracker.report("tick", ex);  // 4th = exhausted
        assertTrue(tracker.openCount() <= 0, "Exhausted bug should not count as open");
    }
}
