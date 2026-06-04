package de.metis.kernel.self;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 12a BugTracker.
 */
class BugTrackerTest {

    @Test
    void testReportNewBug() {
        var tracker = new BugTracker();
        boolean triggered = tracker.report("test", new RuntimeException("test error"));
        assertTrue(triggered, "New bug should be reported");
        assertEquals(1, tracker.size());
        assertEquals(1, tracker.openCount());
    }

    @Test
    void testDeduplicateSameBug() {
        var tracker = new BugTracker();
        RuntimeException ex = new RuntimeException("dup");
        boolean first = tracker.report("test", ex);
        assertTrue(first, "First report should trigger");
        // Same exception again (same stacktrace, within cooldown) → false
        boolean second = tracker.report("test", ex);
        assertFalse(second, "Same bug within cooldown should not re-trigger");
        assertEquals(1, tracker.size(), "Same bug should not increase size");
    }

    @Test
    void testMaxStored() {
        var tracker = new BugTracker();
        // Report 25 different bugs (ring buffer = 20)
        for (int i = 0; i < 25; i++) {
            tracker.report("test", new RuntimeException("bug-" + i));
        }
        assertEquals(20, tracker.size(), "Ring buffer should cap at 20");
    }

    @Test
    void testMultipleBugTypes() {
        var tracker = new BugTracker();
        tracker.report("tick", new NullPointerException("npe"));
        tracker.report("planner", new IllegalArgumentException("bad arg"));
        tracker.report("telegram", new RuntimeException("timeout"));
        assertEquals(3, tracker.size());
    }

    @Test
    void testFixGoalTrigger() {
        var tracker = new BugTracker();
        final String[] triggered = {null};
        tracker.withFixGoalTrigger(desc -> triggered[0] = desc);
        tracker.report("test", new RuntimeException("fix me"));
        assertNotNull(triggered[0], "Should trigger fix callback");
        assertTrue(triggered[0].contains("fix me"));
    }
}
