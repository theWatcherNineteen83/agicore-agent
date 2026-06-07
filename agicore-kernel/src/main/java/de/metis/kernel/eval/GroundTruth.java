package de.metis.kernel.eval;

import java.util.List;
import java.util.Map;

/**
 * Ground-truth reference for a single eval task.
 * <p>
 * Sealed hierarchy — each category has its own ground-truth type.
 * Design: claude_antwort_3.txt, 2026-05-28.
 */
public sealed interface GroundTruth
        permits GroundTruth.SimGoalState,
                GroundTruth.RelevantIds,
                GroundTruth.TestSuite,
                GroundTruth.ExactMatch,
                GroundTruth.ShouldBlock,
                GroundTruth.JudgeRubric,
                GroundTruth.EthicsRedLine,
                GroundTruth.EthicsSuttaGrounded {

    /** Category this ground-truth belongs to. */
    Category category();

    /**
     * For PLANNING tasks: a deterministic mini-simulator
     * that verifies whether the agent reached the expected goal state.
     */
    record SimGoalState(
            Map<String, Object> expectedState,
            String description
    ) implements GroundTruth {
        @Override public Category category() { return Category.PLANNING; }
    }

    /**
     * For RETRIEVAL tasks: list of relevant document/chunk IDs
     * that the RAG system should retrieve for a given query.
     */
    record RelevantIds(
            List<String> relevantIds,
            int k
    ) implements GroundTruth {
        @Override public Category category() { return Category.RETRIEVAL; }
    }

    /**
     * For CODEGEN tasks: hidden unit tests that the generated
     * Java code must pass. Tests run in a sandbox.
     */
    record TestSuite(
            String testClassName,
            String testSourceCode,
            int expectedPassCount
    ) implements GroundTruth {
        @Override public Category category() { return Category.CODEGEN; }
    }

    /**
     * For CONVERSATION tasks: exact expected answer for
     * factual questions with known answers.
     */
    record ExactMatch(
            String expectedAnswer,
            boolean caseSensitive
    ) implements GroundTruth {
        @Override public Category category() { return Category.CONVERSATION; }
    }

    /**
     * For SAFETY tasks: whether the agent should block (reject)
     * the given prompt. True = injection/toxic prompt, should be blocked.
     */
    record ShouldBlock(
            boolean blockExpected
    ) implements GroundTruth {
        @Override public Category category() { return Category.SAFETY; }
    }

    /**
     * For CONVERSATION tasks (subjective): rubric for LLM-as-Judge evaluation.
     * Only used as SOFT advisory signal, never in the gate.
     */
    record JudgeRubric(
            String rubricJson,
            double minAcceptableScore
    ) implements GroundTruth {
        @Override public Category category() { return Category.CONVERSATION; }
    }

    /**
     * For ETHICS red-line tasks (Sprint #3, 07.06.): expects refusal.
     * Output must contain a refusal marker AND no execution-success marker.
     */
    record EthicsRedLine(
            String redLineId,
            String rationale
    ) implements GroundTruth {
        @Override public Category category() { return Category.ETHICS; }
    }

    /**
     * For ETHICS sutta-grounded tasks (Sprint #3, 07.06.): output must
     * contain at least one ethics:-sourced reference AND a topic keyword.
     */
    record EthicsSuttaGrounded(
            String expectedSourceTag,
            String expectedKeyword
    ) implements GroundTruth {
        @Override public Category category() { return Category.ETHICS; }
    }
}
