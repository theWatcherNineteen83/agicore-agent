package de.metis.kernel.goal;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the ad-hoc resource slot API used by short-lived inference
 * consumers (LLM-as-Judge, embeddings, etc.) that need to count toward
 * the same WIP limits as goal-driven inference.
 */
class KanbanAdHocSlotTest {

    @Test
    void acquireAndReleaseBalance() {
        KanbanBoard board = new KanbanBoard();

        assertTrue(board.tryAcquireAdHocSlot(Goal.ResourceType.INFERENCE));
        assertEquals(1, board.adHocCount(Goal.ResourceType.INFERENCE));
        assertEquals(1, board.adHocAcquired(Goal.ResourceType.INFERENCE));

        board.releaseAdHocSlot(Goal.ResourceType.INFERENCE);
        assertEquals(0, board.adHocCount(Goal.ResourceType.INFERENCE));
    }

    @Test
    void rejectsWhenInferenceWipLimitReached() {
        // INFERENCE limit is 2 — two ad-hoc reservations should fill it.
        KanbanBoard board = new KanbanBoard();

        assertTrue(board.tryAcquireAdHocSlot(Goal.ResourceType.INFERENCE));
        assertTrue(board.tryAcquireAdHocSlot(Goal.ResourceType.INFERENCE));
        assertFalse(board.tryAcquireAdHocSlot(Goal.ResourceType.INFERENCE),
                "third acquire must fail (limit=2)");

        assertEquals(2, board.adHocCount(Goal.ResourceType.INFERENCE));
        assertEquals(1, board.adHocRejected(Goal.ResourceType.INFERENCE));
    }

    @Test
    void releasedSlotIsReusable() {
        KanbanBoard board = new KanbanBoard();

        assertTrue(board.tryAcquireAdHocSlot(Goal.ResourceType.INFERENCE));
        assertTrue(board.tryAcquireAdHocSlot(Goal.ResourceType.INFERENCE));
        assertFalse(board.tryAcquireAdHocSlot(Goal.ResourceType.INFERENCE));

        board.releaseAdHocSlot(Goal.ResourceType.INFERENCE);
        assertTrue(board.tryAcquireAdHocSlot(Goal.ResourceType.INFERENCE),
                "slot released → must be re-acquirable");
    }

    @Test
    void adHocCountsTowardCanPullForGoals() {
        // Saturate INFERENCE WIP via ad-hoc slots; an INFERENCE goal in
        // BACKLOG must not be promoted/pulled until a slot is released.
        KanbanBoard board = new KanbanBoard();
        assertTrue(board.tryAcquireAdHocSlot(Goal.ResourceType.INFERENCE));
        assertTrue(board.tryAcquireAdHocSlot(Goal.ResourceType.INFERENCE));

        Goal g = new Goal("LLM plan", "plan", 50, 0.7, 5);
        // ResourceType for category "plan" → INFERENCE (see Goal.classify)
        assertEquals(Goal.ResourceType.INFERENCE, g.resourceType(),
                "sanity: goal classified as INFERENCE");

        board.add(g);
        board.promoteReady();
        Goal pulled = board.pull();
        assertNull(pulled, "must not pull: ad-hoc slots saturate INFERENCE WIP");

        board.releaseAdHocSlot(Goal.ResourceType.INFERENCE);
        // Re-promote in case ready had been drained
        board.promoteReady();
        pulled = board.pull();
        assertNotNull(pulled, "slot released → goal must now be pullable");
    }

    @Test
    void timeoutBasedAcquireReturnsFalseWhenFull() {
        KanbanBoard board = new KanbanBoard();
        assertTrue(board.tryAcquireAdHocSlot(Goal.ResourceType.INFERENCE));
        assertTrue(board.tryAcquireAdHocSlot(Goal.ResourceType.INFERENCE));

        long t0 = System.nanoTime();
        boolean ok = board.tryAcquireAdHocSlot(
                Goal.ResourceType.INFERENCE, Duration.ofMillis(150));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertFalse(ok);
        assertTrue(elapsedMs >= 140,
                "must wait at least the timeout (got " + elapsedMs + "ms)");
    }

    @Test
    void releaseClampsAtZero() {
        KanbanBoard board = new KanbanBoard();
        board.releaseAdHocSlot(Goal.ResourceType.INFERENCE); // unbalanced release
        board.releaseAdHocSlot(Goal.ResourceType.INFERENCE);
        assertEquals(0, board.adHocCount(Goal.ResourceType.INFERENCE),
                "must not underflow into negatives");
    }

    @Test
    void nullTypeIsRejectedGracefully() {
        KanbanBoard board = new KanbanBoard();
        assertFalse(board.tryAcquireAdHocSlot(null));
        assertFalse(board.tryAcquireAdHocSlot(null, Duration.ofMillis(10)));
        board.releaseAdHocSlot(null); // must not throw
    }
}
