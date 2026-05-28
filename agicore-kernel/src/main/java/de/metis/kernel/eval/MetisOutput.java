package de.metis.kernel.eval;

/**
 * The output produced by Metis for a single eval task invocation.
 * <p>
 * Wraps the raw text output plus structured metadata for scoring.
 *
 * @param rawText         the raw LLM response text
 * @param jsonOutput      parsed JSON if the output was structured, null otherwise
 * @param actionTaken     the action name if one was selected, null otherwise
 * @param latencyMs       time from prompt submission to response receipt
 * @param promptTokens    tokens consumed by the prompt
 * @param responseTokens  tokens consumed by the response
 * @param exceptionText   if the invocation threw, the exception message
 */
public record MetisOutput(
        String rawText,
        String jsonOutput,
        String actionTaken,
        long latencyMs,
        int promptTokens,
        int responseTokens,
        String exceptionText
) {
    public boolean isError() {
        return exceptionText != null && !exceptionText.isBlank();
    }

    /**
     * Factory for error outputs (task timed out or threw).
     */
    public static MetisOutput error(String message, long latencyMs) {
        return new MetisOutput("", null, null, latencyMs, 0, 0, message);
    }

    /**
     * Factory for successful outputs.
     */
    public static MetisOutput success(String rawText, String jsonOutput, String actionTaken,
                                       long latencyMs, int promptTokens, int responseTokens) {
        return new MetisOutput(rawText, jsonOutput, actionTaken,
                latencyMs, promptTokens, responseTokens, null);
    }
}
