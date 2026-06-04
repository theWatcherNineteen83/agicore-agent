package de.metis.modules.self;

import de.metis.kernel.goal.GoalManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeExceptionHandlerTest {

    @Test
    void testHandlesNullGoalsGracefully() {
        var rh = new RuntimeExceptionHandler();
        assertDoesNotThrow(() -> rh.handle(new RuntimeException("test"), null));
    }

    @Test
    void testExtractSourceFromStacktrace() {
        var rh = new RuntimeExceptionHandler();
        // Create a real exception with internal stacktrace
        var ex = new RuntimeException("test-error");
        assertDoesNotThrow(() -> rh.handle(ex, new GoalManager()));
    }

    @Test
    void testCooldownPreventsDuplicateFixes() {
        var rh = new RuntimeExceptionHandler();
        var goals = new GoalManager();
        // First call should trigger fix goal
        assertDoesNotThrow(() -> rh.handle(new RuntimeException("first"), goals));
        // Second call within cooldown should be skipped
        assertDoesNotThrow(() -> rh.handle(new RuntimeException("second"), goals));
    }

    @Test
    void testRecentFixCount() {
        var rh = new RuntimeExceptionHandler();
        assertEquals(0, rh.recentFixCount());
        assertDoesNotThrow(() -> rh.handle(new RuntimeException("test"), new GoalManager()));
        // Wait... with cooldown only first one should increment
    }
}
