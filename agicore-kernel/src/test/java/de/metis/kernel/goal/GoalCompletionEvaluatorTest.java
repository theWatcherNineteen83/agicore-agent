package de.metis.kernel.goal;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GoalCompletionEvaluatorTest {

    @Test
    void parse_blockSyntax() {
        String desc = """
                Beschreibung blah blah.
                [postconditions]
                beliefs_with_source_prefix:ethics: >= 50
                child_goals_done >= 3
                # Kommentar - wird ignoriert
                [/postconditions]
                Nachtext.
                """;
        var conds = GoalCompletionEvaluator.parse(desc);
        assertEquals(2, conds.size());
        assertEquals("beliefs_with_source_prefix:ethics:", conds.get(0).lhs());
        assertEquals(">=", conds.get(0).op());
        assertEquals(50, conds.get(0).threshold());
        assertEquals("child_goals_done", conds.get(1).lhs());
    }

    @Test
    void parse_missingBlockReturnsEmpty() {
        assertTrue(GoalCompletionEvaluator.parse(null).isEmpty());
        assertTrue(GoalCompletionEvaluator.parse("kein Block").isEmpty());
        assertTrue(GoalCompletionEvaluator.parse("[postconditions]ohne ende").isEmpty());
    }

    @Test
    void evaluate_allConditionsMet_marksDone() {
        var hierarchy = new GoalHierarchy();
        Map<String, Integer> counts = new HashMap<>();
        counts.put("ethics:", 60);
        GoalCompletionEvaluator.BeliefCounter simple =
                prefix -> counts.getOrDefault(prefix, 0);

        var g = new LongHorizonGoal(
                null, "Test-Goal",
                """
                [postconditions]
                beliefs_with_source_prefix:ethics: >= 50
                [/postconditions]
                """,
                GoalHorizon.STRATEGIC,
                LongHorizonGoal.Status.ACTIVE,
                null, List.of(),
                null, null, null, null, 0.0,
                10, "metis",
                List.of("test"));
        hierarchy.upsert(g);

        var eval = new GoalCompletionEvaluator(hierarchy, simple);
        var report = eval.evaluateAll();

        assertEquals(1, report.evaluated());
        assertEquals(1, report.completed());
        assertEquals(0, report.progressUpdated());

        var updated = hierarchy.all().get(0);
        assertEquals(LongHorizonGoal.Status.DONE, updated.status());
        assertEquals(1.0, updated.progress(), 0.001);
    }

    @Test
    void evaluate_partialMet_updatesProgress() {
        var hierarchy = new GoalHierarchy();
        GoalCompletionEvaluator.BeliefCounter c = prefix ->
                prefix.equals("ethics:") ? 30 : 0;

        var g = new LongHorizonGoal(
                null, "Partial",
                """
                [postconditions]
                beliefs_with_source_prefix:ethics: >= 100
                beliefs_with_source_prefix:wiki: >= 100
                [/postconditions]
                """,
                GoalHorizon.STRATEGIC, LongHorizonGoal.Status.ACTIVE,
                null, List.of(), null, null, null, null, 0.0,
                10, "metis", List.of());
        hierarchy.upsert(g);

        var eval = new GoalCompletionEvaluator(hierarchy, c);
        var report = eval.evaluateAll();

        assertEquals(0, report.completed());
        // 0 of 2 conditions met (ethics 30 < 100, wiki 0 < 100) -> progress 0/2 = 0
        var updated = hierarchy.all().get(0);
        assertEquals(0.0, updated.progress(), 0.001);
        assertEquals(LongHorizonGoal.Status.ACTIVE, updated.status());
    }

    @Test
    void evaluate_progressMonotonic() {
        var hierarchy = new GoalHierarchy();
        // Counter returns enough to satisfy first condition only.
        GoalCompletionEvaluator.BeliefCounter c = prefix ->
                prefix.equals("ethics:") ? 60 : 0;

        var g = new LongHorizonGoal(
                null, "Halb",
                """
                [postconditions]
                beliefs_with_source_prefix:ethics: >= 50
                beliefs_with_source_prefix:wiki: >= 100
                [/postconditions]
                """,
                GoalHorizon.STRATEGIC, LongHorizonGoal.Status.ACTIVE,
                null, List.of(), null, null, null, null, 0.0,
                10, "metis", List.of());
        hierarchy.upsert(g);

        var eval = new GoalCompletionEvaluator(hierarchy, c);
        var report = eval.evaluateAll();

        assertEquals(1, report.progressUpdated());
        assertEquals(0, report.completed());
        var updated = hierarchy.all().get(0);
        assertEquals(0.5, updated.progress(), 0.001);
    }

    @Test
    void evaluate_doneGoalsIgnored() {
        var hierarchy = new GoalHierarchy();
        GoalCompletionEvaluator.BeliefCounter c = prefix -> 0;

        var done = new LongHorizonGoal(
                null, "Already done",
                "[postconditions]\nbeliefs_with_source_prefix:x: >= 1\n[/postconditions]",
                GoalHorizon.STRATEGIC, LongHorizonGoal.Status.DONE,
                null, List.of(), null, null, null, null, 1.0,
                10, "metis", List.of());
        hierarchy.upsert(done);

        var eval = new GoalCompletionEvaluator(hierarchy, c);
        var report = eval.evaluateAll();
        assertEquals(0, report.evaluated());
    }

    @Test
    void evaluate_noPostconditions_leavesGoalUntouched() {
        var hierarchy = new GoalHierarchy();
        var g = new LongHorizonGoal(
                null, "Free-form",
                "Ich bin eine freie Beschreibung ohne strukturierte Postconditions.",
                GoalHorizon.TACTICAL, LongHorizonGoal.Status.ACTIVE,
                null, List.of(), null, null, null, null, 0.42,
                10, "metis", List.of());
        hierarchy.upsert(g);

        var eval = new GoalCompletionEvaluator(hierarchy, p -> 0);
        var report = eval.evaluateAll();
        assertEquals(0, report.evaluated());
        assertEquals(0.42, hierarchy.all().get(0).progress(), 0.001);
    }
}
