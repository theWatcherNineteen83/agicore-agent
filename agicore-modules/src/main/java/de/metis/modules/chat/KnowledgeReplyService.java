package de.metis.modules.chat;

import de.metis.kernel.persistence.KnowledgeStore;
import de.metis.kernel.world.Belief;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Answers questions from Metis's own knowledge (Beliefs + Experiences).
 * <p>
 * First line of response: searches WorldModel beliefs for relevant facts.
 * Falls back to null if confidence is too low — caller should then use LLM.
 */
public class KnowledgeReplyService {

    private static final Logger LOG = Logger.getLogger(KnowledgeReplyService.class.getName());

    private static final double MIN_CONFIDENCE = 0.7;
    private static final int MAX_RESULTS = 5;

    private final KnowledgeStore store;

    public KnowledgeReplyService(KnowledgeStore store) {
        this.store = store;
    }

    /** Source prefixes to exclude from knowledge replies (internal metas). */
    private static final Set<String> EXCLUDED_SOURCES = Set.of(
            "bootstrap", "bootstrap:", "persona", "system"
    );

    /** Patterns that indicate a belief is internal/debug data, not factual knowledge. */
    private static final List<String> INTERNAL_PATTERNS = List.of(
            "Action:", "Goal:", "? success", "EDI: ?",
            "You are EDI", "Core traits:", "You have access to:",
            "Conversation context:", "Phase 2", "Linux miniedi",
            "HTTP 200 GET", "From my knowledge",
            "action for goal"
    );

    private static final int MIN_STATEMENT_LENGTH = 10;
    private static final int MAX_STATEMENT_LENGTH = 500;

    /**
     * Try to answer a question from Metis's own knowledge.
     *
     * @param question the user's question
     * @return a response string, or null if knowledge is insufficient
     */
    public String tryAnswer(String question) {
        if (store == null) return null;

        try {
            // Extract keywords from question
            var keywords = extractKeywords(question);
            if (keywords.isEmpty()) return null;

            // Search beliefs for matching knowledge
            List<Belief> matches = store.searchBeliefs(keywords, MAX_RESULTS * 3); // oversample, then filter
            if (matches.isEmpty()) return null;

            // Filter out internal/metadata beliefs
            var validBeliefs = matches.stream()
                    .filter(b -> !isExcludedSource(b.source()))
                    .filter(b -> !containsInternalPattern(b.statement()))
                    .filter(b -> {
                        int len = b.statement().length();
                        return len >= MIN_STATEMENT_LENGTH && len <= MAX_STATEMENT_LENGTH;
                    })
                    .limit(MAX_RESULTS)
                    .toList();

            if (validBeliefs.isEmpty()) return null;

            // Check if we have enough confidence
            double avgConfidence = validBeliefs.stream()
                    .mapToDouble(Belief::confidence)
                    .average().orElse(0);

            if (avgConfidence < MIN_CONFIDENCE) {
                LOG.fine("Knowledge confidence too low: " + String.format("%.2f", avgConfidence));
                return null;
            }

            // Build response from matching beliefs
            return buildResponse(validBeliefs, avgConfidence);

        } catch (Exception e) {
            LOG.warning("Knowledge reply failed: " + e.getMessage());
            return null;
        }
    }

    private boolean isExcludedSource(String source) {
        if (source == null) return true;
        return EXCLUDED_SOURCES.stream().anyMatch(source::startsWith);
    }

    private boolean containsInternalPattern(String statement) {
        if (statement == null) return true;
        return INTERNAL_PATTERNS.stream().anyMatch(statement::contains);
    }

    /**
     * Extract meaningful keywords from a question.
     */
    private List<String> extractKeywords(String text) {
        String lower = text.toLowerCase()
                .replaceAll("[?.!,;:]", " ")
                .replaceAll("\\s+", " ")
                .strip();

        var stopWords = Set.of("the", "is", "a", "an", "what", "how", "why", "when",
                "where", "who", "can", "you", "tell", "me", "about", "bitte", "ist",
                "ein", "eine", "der", "die", "das", "und", "oder", "von", "mit",
                "auf", "für", "bei", "zu", "im", "am", "um", "was", "wie", "wo",
                "wann", "wer", "kannst", "mir", "sagen", "über");

        return Arrays.stream(lower.split(" "))
                .map(String::strip)
                .filter(w -> w.length() >= 3)
                .filter(w -> !stopWords.contains(w))
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Build a natural-language response from knowledge matches.
     */
    private String buildResponse(List<Belief> matches, double confidence) {
        var facts = matches.stream()
                .map(Belief::statement)
                .distinct()
                .limit(3)
                .toList();

        if (facts.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("From my knowledge (confidence: ")
                .append(String.format("%.0f%%", confidence * 100))
                .append("):\n");

        for (var fact : facts) {
            sb.append("• ").append(capitalize(fact)).append(".\n");
        }

        sb.append("\n— EDI, aus eigener Erfahrung");
        return sb.toString();
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
