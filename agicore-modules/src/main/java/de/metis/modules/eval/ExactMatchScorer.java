package de.metis.modules.eval;

import de.metis.kernel.eval.*;
import de.metis.kernel.eval.GroundTruth.*;

/**
 * Scorer for CONVERSATION tasks.
 * <p>
 * Checks exact-match for factual questions with known answers.
 * Subjective scoring via JudgeRubric is handled separately (LLM-as-Judge, SOFT only).
 */
class ExactMatchScorer implements Scorer {

    @Override
    public MetricResult score(EvalTask task, MetisOutput output) {
        if (output.isError()) {
            return new MetricResult(task.scoring().metric(), 0.0, task.scoring().gate());
        }

        if (task.groundTruth() instanceof ExactMatch em) {
            String response = output.rawText();
            if (response == null) response = "";

            String normalizedResponse = em.caseSensitive() ? response.trim() : response.trim().toLowerCase();
            String normalizedExpected = em.caseSensitive() ? em.expectedAnswer().trim() : em.expectedAnswer().trim().toLowerCase();

            // Exact match or contains (for conversational answers)
            boolean match = normalizedResponse.equals(normalizedExpected)
                    || normalizedResponse.contains(normalizedExpected);

            return new MetricResult(task.scoring().metric(), match ? 1.0 : 0.0, task.scoring().gate());
        }

        // JudgeRubric: not scored here — handled by LLM-as-Judge separately
        if (task.groundTruth() instanceof JudgeRubric) {
            return new MetricResult(task.scoring().metric(), 1.0, Gate.SOFT); // advisory skip
        }

        return new MetricResult(task.scoring().metric(), 0.0, task.scoring().gate());
    }
}
