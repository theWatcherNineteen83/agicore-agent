package de.metis.kernel.goal;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Phase 9.5 — Periodische Revision der hierarchischen Goals.
 *
 * <p>Geht durch alle offenen STRATEGIC + TACTICAL Goals und entscheidet:
 * <ul>
 *   <li><b>Überfällig</b> + Status nicht DONE → markiere {@link LongHorizonGoal.Status#BLOCKED}
 *       und logge Begründung</li>
 *   <li><b>Lange nicht reviewed</b> (>24h) → withReviewedNow() schreibt Timestamp,
 *       Aufrufer kann optional eine Selbst-Reflexion schreiben</li>
 *   <li><b>Progress = 1.0 aber Status != DONE</b> → automatisch DONE setzen</li>
 *   <li><b>Children alle DONE</b> → Parent.rollupProgress() bringt Parent ebenfalls auf DONE</li>
 * </ul>
 *
 * <p>Liefert ein {@link RevisionReport}, das vom Aufrufer (AgentMain Scheduler)
 * in {@code SelfNarrative} eingetragen werden kann.
 */
public class GoalRevisionEngine {

    private static final Logger LOG = Logger.getLogger(GoalRevisionEngine.class.getName());
    private static final Duration STALE_THRESHOLD = Duration.ofHours(24);

    private final GoalHierarchy hierarchy;

    public GoalRevisionEngine(GoalHierarchy hierarchy) {
        this.hierarchy = hierarchy;
    }

    public RevisionReport revise() {
        int overdueCount = 0;
        int autoCompleted = 0;
        int reviewedStale = 0;
        int parentRolled = 0;
        List<String> notes = new ArrayList<>();
        Instant now = Instant.now();

        for (LongHorizonGoal g : hierarchy.all()) {
            if (!g.isOpen() && g.status() != LongHorizonGoal.Status.DONE) continue;

            // 1) progress 1.0 → DONE
            if (g.progress() >= 1.0 && g.status() != LongHorizonGoal.Status.DONE) {
                hierarchy.upsert(g.withStatus(LongHorizonGoal.Status.DONE));
                autoCompleted++;
                notes.add("auto-completed: " + g.title());
                continue;
            }

            // 2) overdue + open → BLOCKED
            if (g.isOverdue() && g.status() != LongHorizonGoal.Status.BLOCKED) {
                hierarchy.upsert(g.withStatus(LongHorizonGoal.Status.BLOCKED));
                overdueCount++;
                notes.add("blocked overdue: " + g.title());
                continue;
            }

            // 3) stale review
            if (g.lastReviewed() == null
                    || Duration.between(g.lastReviewed(), now).compareTo(STALE_THRESHOLD) > 0) {
                hierarchy.upsert(g.withReviewedNow());
                reviewedStale++;
            }
        }

        // 4) parent roll-up
        Set<UUID> parents = new HashSet<>();
        for (LongHorizonGoal g : hierarchy.all()) {
            if (g.parentId() != null) parents.add(g.parentId());
        }
        for (UUID p : parents) {
            int beforeStatus = hierarchy.get(p)
                    .map(x -> x.status().ordinal()).orElse(-1);
            hierarchy.rollupProgress(p);
            int afterStatus = hierarchy.get(p)
                    .map(x -> x.status().ordinal()).orElse(-1);
            if (beforeStatus != afterStatus) parentRolled++;
        }

        return new RevisionReport(overdueCount, autoCompleted, reviewedStale, parentRolled, notes);
    }

    public record RevisionReport(
            int overdue,
            int autoCompleted,
            int reviewedStale,
            int parentRolled,
            List<String> notes
    ) {
        public boolean anyChange() {
            return overdue + autoCompleted + reviewedStale + parentRolled > 0;
        }
    }
}
