package de.metis.modules.eval;

import de.metis.kernel.eval.*;
import de.metis.kernel.eval.GroundTruth.*;
import java.util.*;

/**
 * Scorer for RETRIEVAL tasks.
 * <p>
 * Computes Recall@k: fraction of relevant documents retrieved within the top-k results.
 * Ground truth: RelevantIds (list of expected document/chunk IDs).
 */
class RecallScorer implements Scorer {

    @Override
    public MetricResult score(EvalTask task, MetisOutput output) {
        if (output.isError()) {
            return new MetricResult(task.scoring().metric(), 0.0, task.scoring().gate());
        }

        if (!(task.groundTruth() instanceof RelevantIds relevant)) {
            return new MetricResult(task.scoring().metric(), 0.0, task.scoring().gate());
        }

        // Parse retrieved IDs from output (expects JSON array of IDs)
        Set<String> retrieved = parseRetrievedIds(output.rawText(), relevant.k());
        Set<String> relevantSet = new HashSet<>(relevant.relevantIds());

        if (relevantSet.isEmpty()) return new MetricResult(task.scoring().metric(), 1.0, task.scoring().gate());

        // Recall@k: intersection size / relevant size
        long intersection = retrieved.stream().filter(relevantSet::contains).count();
        double recall = (double) intersection / relevantSet.size();

        return new MetricResult("recall@" + relevant.k(), recall, task.scoring().gate());
    }

    /**
     * Extract up to k IDs from the output text.
     * Expects JSON array format like ["id1", "id2", ...] or comma-separated list.
     */
    private Set<String> parseRetrievedIds(String rawText, int k) {
        Set<String> ids = new LinkedHashSet<>();
        if (rawText == null || rawText.isBlank()) return ids;

        // Try JSON array: ["id1", "id2", ...]
        int start = rawText.indexOf('[');
        int end = rawText.indexOf(']', start);
        if (start >= 0 && end > start) {
            String array = rawText.substring(start + 1, end);
            for (String part : array.split(",")) {
                String id = part.trim().replaceAll("^[\"']|[\"']$", "");
                if (!id.isBlank()) {
                    ids.add(id);
                    if (ids.size() >= k) break;
                }
            }
            return ids;
        }

        // Fallback: comma-separated
        for (String part : rawText.split(",")) {
            String id = part.trim();
            if (!id.isBlank()) {
                ids.add(id);
                if (ids.size() >= k) break;
            }
        }
        return ids;
    }
}
