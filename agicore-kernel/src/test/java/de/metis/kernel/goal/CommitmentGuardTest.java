package de.metis.kernel.goal;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 9.5 — CommitmentGuard.
 *
 * <p>Deterministisch, kein LLM/Netz. Prüft, dass HARD-Commitments nicht ohne
 * Begründung abgebrochen werden dürfen, SOFT-Commitments geflaggt werden und
 * Nicht-Commitments frei abbrechbar sind.
 */
class CommitmentGuardTest {

    private static LongHorizonGoal goal(int priority, List<String> tags,
                                        LongHorizonGoal.Status status) {
        return new LongHorizonGoal(
                null, "Versprechen an Georg: melde mich um 18:00",
                "test", GoalHorizon.OPERATIONAL, status,
                null, List.of(), Instant.now(), Instant.now().plusSeconds(3600),
                null, null, 0.0, priority, "Georg", tags);
    }

    @Test
    void hardCommitmentWithoutReasonIsDenied() {
        var guard = new CommitmentGuard();
        var g = goal(85, List.of("commitment", "person:Georg"), LongHorizonGoal.Status.ACTIVE);

        assertTrue(CommitmentGuard.isHardCommitment(g));
        var d = guard.evaluateAbandon(g, null);
        assertEquals(CommitmentGuard.Verdict.DENIED, d.verdict());
        assertTrue(d.isDenied());
        assertFalse(guard.mayAbandon(g, "   "));
        // zwei DENIED-Bewertungen (direkter Aufruf + mayAbandon)
        assertEquals(2, guard.deniedCount());
    }

    @Test
    void hardCommitmentWithReasonIsFlaggedNotDenied() {
        var guard = new CommitmentGuard();
        var g = goal(90, List.of("commitment", "person:Georg"), LongHorizonGoal.Status.ACTIVE);

        var d = guard.evaluateAbandon(g, "Deadline durch höher priorisiertes Sicherheitsziel verdrängt");
        assertEquals(CommitmentGuard.Verdict.FLAGGED, d.verdict());
        assertTrue(d.isAllowed());
        assertTrue(guard.mayAbandon(g, "guter Grund"));
        assertEquals(0, guard.deniedCount());
    }

    @Test
    void softCommitmentIsFlaggedButAllowed() {
        var guard = new CommitmentGuard();
        var g = goal(50, List.of("commitment"), LongHorizonGoal.Status.ACTIVE);

        assertFalse(CommitmentGuard.isHardCommitment(g));
        assertTrue(CommitmentGuard.isSoftCommitment(g));
        var d = guard.evaluateAbandon(g, null);
        assertEquals(CommitmentGuard.Verdict.FLAGGED, d.verdict());
        assertTrue(d.isAllowed());
    }

    @Test
    void nonCommitmentIsAllowed() {
        var guard = new CommitmentGuard();
        var g = goal(95, List.of("lifetime", "edi"), LongHorizonGoal.Status.ACTIVE);

        assertFalse(CommitmentGuard.isHardCommitment(g));
        assertFalse(CommitmentGuard.isSoftCommitment(g));
        var d = guard.evaluateAbandon(g, null);
        assertEquals(CommitmentGuard.Verdict.ALLOWED, d.verdict());
        assertTrue(guard.mayAbandon(g, null));
        // zwei ALLOWED-Bewertungen (direkter Aufruf + mayAbandon)
        assertEquals(2, guard.allowedCount());
    }

    @Test
    void closedCommitmentIsNoLongerHard() {
        var guard = new CommitmentGuard();
        var g = goal(85, List.of("commitment"), LongHorizonGoal.Status.DONE);

        // DONE → isOpen() == false → kein HARD-Commitment mehr
        assertFalse(CommitmentGuard.isHardCommitment(g));
        var d = guard.evaluateAbandon(g, null);
        assertEquals(CommitmentGuard.Verdict.ALLOWED, d.verdict());
    }

    @Test
    void nullGoalIsAllowed() {
        var guard = new CommitmentGuard();
        var d = guard.evaluateAbandon(null, null);
        assertEquals(CommitmentGuard.Verdict.ALLOWED, d.verdict());
        assertTrue(guard.mayAbandon(null, null));
    }
}
