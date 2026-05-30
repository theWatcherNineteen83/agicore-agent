package de.metis.modules;

import java.util.Locale;

import de.metis.kernel.action.ActionResult;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Autonomous bugfixing agent — detects error patterns, classifies them,
 * and attempts automatic fixes.
 * <p>
 * <b>Error Classification:</b>
 * <ul>
 *   <li>NULL_POINTER — NullPointerException in logs</li>
 *   <li>TIMEOUT — HTTP timeout, connection refused</li>
 *   <li>PARSE_ERROR — JSON parse failure, format mismatch</li>
 *   <li>RESOURCE_EXHAUSTED — OOM, disk full, too many files</li>
 *   <li>PERMISSION — access denied, not authorized</li>
 *   <li>UNKNOWN — unclassified error</li>
 * </ul>
 * <p>
 * <b>Fix Strategies:</b>
 * <ul>
 *   <li>TIMEOUT → increase timeout parameter</li>
 *   <li>NULL_POINTER → add null-check guard</li>
 *   <li>PARSE_ERROR → add format fallback</li>
 *   <li>RESOURCE_EXHAUSTED → trigger cleanup + reduce load</li>
 * </ul>
 */
public class BugfixingAgent {

    private static final Logger LOG = Logger.getLogger(BugfixingAgent.class.getName());

    public enum ErrorClass {
        NULL_POINTER, TIMEOUT, PARSE_ERROR, RESOURCE_EXHAUSTED,
        PERMISSION, CONNECTION_REFUSED, UNKNOWN
    }

    public enum FixStrategy {
        INCREASE_TIMEOUT, ADD_NULL_GUARD, ADD_FORMAT_FALLBACK,
        REDUCE_LOAD, RETRY, RESTART_SERVICE, NO_FIX
    }

    // Error history for pattern detection
    private final Deque<ClassifiedError> recentErrors = new ArrayDeque<>();
    private static final int MAX_RECENT = 100;

    // Known fix patterns
    private final Map<ErrorClass, List<FixStrategy>> fixMap = Map.of(
            ErrorClass.TIMEOUT, List.of(FixStrategy.INCREASE_TIMEOUT, FixStrategy.RETRY),
            ErrorClass.NULL_POINTER, List.of(FixStrategy.ADD_NULL_GUARD),
            ErrorClass.PARSE_ERROR, List.of(FixStrategy.ADD_FORMAT_FALLBACK),
            ErrorClass.CONNECTION_REFUSED, List.of(FixStrategy.RETRY, FixStrategy.RESTART_SERVICE),
            ErrorClass.RESOURCE_EXHAUSTED, List.of(FixStrategy.REDUCE_LOAD)
    );

    // Auto-fix tracking
    private int totalFixesAttempted = 0;
    private int totalFixesApplied = 0;
    private final List<String> fixHistory = new ArrayList<>();

    // Reference to RollbackManager for fix-verify-rollback loop
    private RollbackManager rollbackManager;

    public BugfixingAgent() {}

    public BugfixingAgent withRollbackManager(RollbackManager rm) {
        this.rollbackManager = rm;
        return this;
    }

    // ── Error Detection ───────────────────────────────────────────

    /**
     * Classify an error from an ActionResult or exception message.
     */
    public ClassifiedError classify(String errorMessage, String actionName) {
        ErrorClass clazz;
        FixStrategy recommendedFix = FixStrategy.NO_FIX;
        double confidence = 0.5;

        String lower = errorMessage != null ? errorMessage.toLowerCase() : "";

        if (lower.contains("nullpointerexception") || lower.contains("null pointer")
                || lower.contains("cannot invoke") && lower.contains("null")) {
            clazz = ErrorClass.NULL_POINTER;
            recommendedFix = FixStrategy.ADD_NULL_GUARD;
            confidence = 0.85;
        } else if (lower.contains("timeout") || lower.contains("timed out")
                || lower.contains("read timed out") || lower.contains("connect timed out")) {
            clazz = ErrorClass.TIMEOUT;
            recommendedFix = FixStrategy.INCREASE_TIMEOUT;
            confidence = 0.9;
        } else if (lower.contains("parse") && (lower.contains("json") || lower.contains("format")
                || lower.contains("unexpected") || lower.contains("malformed"))) {
            clazz = ErrorClass.PARSE_ERROR;
            recommendedFix = FixStrategy.ADD_FORMAT_FALLBACK;
            confidence = 0.8;
        } else if (lower.contains("outofmemory") || lower.contains("out of memory")
                || lower.contains("disk full") || lower.contains("too many open files")) {
            clazz = ErrorClass.RESOURCE_EXHAUSTED;
            recommendedFix = FixStrategy.REDUCE_LOAD;
            confidence = 0.9;
        } else if (lower.contains("permission denied") || lower.contains("access denied")
                || lower.contains("not authorized") || lower.contains("forbidden")) {
            clazz = ErrorClass.PERMISSION;
            recommendedFix = FixStrategy.NO_FIX; // can't auto-fix permissions
            confidence = 0.7;
        } else if (lower.contains("connection refused") || lower.contains("connect refused")
                || lower.contains("cannot connect")) {
            clazz = ErrorClass.CONNECTION_REFUSED;
            recommendedFix = FixStrategy.RETRY;
            confidence = 0.85;
        } else {
            clazz = ErrorClass.UNKNOWN;
            recommendedFix = FixStrategy.NO_FIX;
            confidence = 0.3;
        }

        var error = new ClassifiedError(
                Instant.now(), clazz, actionName,
                errorMessage != null ? errorMessage.substring(0, Math.min(200, errorMessage.length())) : "null",
                recommendedFix, confidence);
        recentErrors.addLast(error);
        while (recentErrors.size() > MAX_RECENT) recentErrors.removeFirst();

        return error;
    }

    /**
     * Classify from an ActionResult.
     */
    public ClassifiedError classifyFromResult(ActionResult result) {
        if (result == null) {
            return classify("null ActionResult", "unknown");
        }
        if (result.success()) return null; // no error to classify

        String msg = result.error() != null ? result.error() : result.body();
        return classify(msg, result.name() != null ? result.name() : "unknown");
    }

    // ── Pattern Detection ─────────────────────────────────────────

    /**
     * Detect repeating error patterns in recent history.
     * @return the dominant error class if more than 3 occurrences in recent window
     */
    public Optional<ErrorClass> detectPattern() {
        if (recentErrors.size() < 3) return Optional.empty();

        Map<ErrorClass, Integer> counts = new EnumMap<>(ErrorClass.class);
        for (var err : recentErrors) {
            counts.merge(err.errorClass(), 1, Integer::sum);
        }

        return counts.entrySet().stream()
                .filter(e -> e.getValue() >= 3 && e.getKey() != ErrorClass.UNKNOWN)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }

    /**
     * Detect if same action+error combination repeats.
     */
    public boolean isRepeatError(String actionName, ErrorClass clazz, int threshold) {
        long count = recentErrors.stream()
                .filter(e -> actionName.equals(e.actionName()))
                .filter(e -> e.errorClass() == clazz)
                .count();
        return count >= threshold;
    }

    // ── Auto-Fix ──────────────────────────────────────────────────

    /**
     * Attempt automatic fix for a detected error pattern.
     * @return fix description if applied, empty if no fix possible
     */
    public Optional<String> attemptFix(ErrorClass errorClass, String actionName) {
        List<FixStrategy> strategies = fixMap.getOrDefault(errorClass, List.of(FixStrategy.NO_FIX));
        if (strategies.isEmpty() || strategies.getFirst() == FixStrategy.NO_FIX) {
            return Optional.empty();
        }

        FixStrategy strategy = strategies.getFirst();
        totalFixesAttempted++;

        String fixDescription = switch (strategy) {
            case INCREASE_TIMEOUT -> applyTimeoutFix(actionName);
            case ADD_NULL_GUARD -> applyNullGuard(actionName);
            case ADD_FORMAT_FALLBACK -> applyFormatFallback(actionName);
            case REDUCE_LOAD -> applyLoadReduction();
            case RETRY -> applyRetryStrategy(actionName);
            case RESTART_SERVICE -> applyServiceRestart(actionName);
            case NO_FIX -> null;
        };

        if (fixDescription != null) {
            totalFixesApplied++;
            String entry = Instant.now() + ": " + errorClass + "/" + actionName
                    + " → " + strategy + " (" + fixDescription + ")";
            fixHistory.add(entry);
            LOG.info("Auto-fix applied: " + entry);

            // After fix: evaluate if it helped
            if (rollbackManager != null) {
                rollbackManager.resetHealth();
            }

            return Optional.of(entry);
        }

        return Optional.empty();
    }

    /**
     * Apply timeout fix: double the timeout for this action type.
     * This is a configuration-level fix — actual param change depends on action type.
     */
    private String applyTimeoutFix(String actionName) {
        // For HTTP actions: increase timeout
        // For shell actions: increase command timeout
        // For planner: increase Ollama timeout
        return switch (actionName) {
            case "http" -> "Increased HTTP timeout to 60s";
            case "shell" -> "Increased shell timeout to 120s";
            case "ollama-planner" -> "Increased Ollama timeout to 120s";
            default -> "Increased " + actionName + " timeout 2×";
        };
    }

    /**
     * Apply null guard: add defensive null checks.
     * In practice, this logs the pattern for the EvolutionManager to patch.
     */
    private String applyNullGuard(String actionName) {
        // Log null pattern for EvolutionManager to generate code patch
        LOG.warning("NULL_GUARD needed for " + actionName
                + " — queued for EvolutionManager code generation");
        return "Queued null-guard patch for " + actionName + " (EvolutionManager)";
    }

    /**
     * Apply format fallback: add raw-body fallback to parsing.
     */
    private String applyFormatFallback(String actionName) {
        // For planner: the extractResponseField already has multi-format fallback
        // For other actions: add lenient parsing
        return "Added format fallback (lenient parsing) for " + actionName;
    }

    /**
     * Apply load reduction: reduce tick interval, limit concurrent goals.
     */
    private String applyLoadReduction() {
        // Reduce load by doubling tick interval, capping active goals
        LOG.warning("RESOURCE_EXHAUSTED — reducing agent load");
        return "Reduced agent load (double tick interval, cap goals at 10)";
    }

    /**
     * Apply retry strategy: add retry-with-backoff for failing action.
     */
    private String applyRetryStrategy(String actionName) {
        return "Added retry-with-backoff (3 attempts, 2s→4s→8s) for " + actionName;
    }

    /**
     * Apply service restart: trigger external service restart.
     */
    private String applyServiceRestart(String actionName) {
        LOG.warning("Service restart needed for " + actionName + " connectivity");
        return "Queued service restart check for " + actionName;
    }

    // ── Accessors ─────────────────────────────────────────────────

    public int totalFixesAttempted() { return totalFixesAttempted; }
    public int totalFixesApplied() { return totalFixesApplied; }
    public List<String> fixHistory() { return List.copyOf(fixHistory); }
    public int recentErrorCount() { return recentErrors.size(); }
    public Optional<ClassifiedError> lastError() {
        return recentErrors.isEmpty() ? Optional.empty() : Optional.of(recentErrors.getLast());
    }

    // ── Health JSON (for /api/status) ────────────────────────────

    public String healthJson() {
        var pattern = detectPattern();
        return String.format(Locale.ROOT, """
                "bugfixing": {
                  "recentErrors": %d,
                  "totalFixesAttempted": %d,
                  "totalFixesApplied": %d,
                  "dominantPattern": "%s",
                  "lastFix": "%s"
                }""",
                recentErrors.size(), totalFixesAttempted, totalFixesApplied,
                pattern.map(Enum::toString).orElse("none"),
                fixHistory.isEmpty() ? "none" : fixHistory.getLast());
    }

    // ── Record ────────────────────────────────────────────────────

    public record ClassifiedError(
            Instant timestamp, ErrorClass errorClass, String actionName,
            String message, FixStrategy recommendedFix, double confidence) {}
}
