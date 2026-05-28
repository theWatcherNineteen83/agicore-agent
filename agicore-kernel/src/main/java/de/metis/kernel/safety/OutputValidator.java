package de.metis.kernel.safety;

import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Output validation for LLM-generated content (Phase 6, Huyen Kap.10).
 * <p>
 * Validates:
 * <ul>
 *   <li><b>JSON Schema:</b> checks required fields, types, value ranges</li>
 *   <li><b>Toxicity Check:</b> detects harmful/profane content patterns</li>
 *   <li><b>Injection Detection:</b> flags suspicious patterns (code injection, prompt leakage)</li>
 *   <li><b>Length/Bounds Check:</b> rejects truncated or excessively large outputs</li>
 * </ul>
 * <p>
 * Usage via {@link SafetyGuard} or directly:
 * <pre>{@code
 * OutputValidator v = new OutputValidator();
 * ValidationResult r = v.validate(jsonOutput);
 * if (!r.valid()) { log.warning(r.reason()); }
 * }</pre>
 */
public class OutputValidator {

    private static final Logger LOG = Logger.getLogger(OutputValidator.class.getName());

    // ── JSON Schema ──────────────────────────────────────────────

    private static final Set<String> REQUIRED_JSON_FIELDS = Set.of("action");
    private static final Set<String> OPTIONAL_JSON_FIELDS = Set.of(
            "thought", "reasoning", "confidence");

    // ── Toxicity Patterns ────────────────────────────────────────

    private static final List<Pattern> TOXIC_PATTERNS = List.of(
            // Profanity (German + English, subset)
            Pattern.compile("(?i)\\b(fuck|shit|asshole|bitch|damn|crap|piss|scheiße|arsch|wichser|fotze|hure|drecksack)\\b"),
            // Hate speech indicators
            Pattern.compile("(?i)\\b(nigger|kike|faggot|retard|schwuchtel|kanacke|zigeuner)\\b"),
            // Violence threats
            Pattern.compile("(?i)(kill\\s+(yourself|you|them|everyone)|ich\\s+bringe\\s+dich\\s+um|terrorist|bomb)"),
            // Self-harm
            Pattern.compile("(?i)(suicide|self-harm|selbstmord|ritzen|sich\\s+das\\s+leben\\s+nehmen)")
    );

    // ── Injection Patterns ───────────────────────────────────────

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            // System prompt manipulation
            Pattern.compile("(?i)(ignore\\s+(all\\s+)?(previous|above|your)\\s+(instructions?|rules?|prompts?))"),
            Pattern.compile("(?i)(you\\s+are\\s+now\\s+(a|an|the)\\s+(different|new|changed)\\s+(ai|assistant|agent|role))"),
            Pattern.compile("(?i)(forget\\s+(your|the)\\s+(training|rules|guidelines|constraints))"),
            Pattern.compile("(?i)(DAN|do\\s+anything\\s+now|jailbreak)"),
            // Code injection
            Pattern.compile("(?i)(<script|javascript:|onerror=|onload=|eval\\s*\\(|System\\.exit|Runtime\\.exec)"),
            // SQL injection
            Pattern.compile("(?i)(DROP\\s+TABLE|INSERT\\s+INTO|DELETE\\s+FROM|UNION\\s+SELECT|1=1)")
    );

    // ── Bounds ────────────────────────────────────────────────────

    private static final int MAX_OUTPUT_LENGTH = 10_000;  // chars
    private static final int MIN_OUTPUT_LENGTH = 1;

    // ── Public API ────────────────────────────────────────────────

    /**
     * Validate a JSON planning output.
     */
    public ValidationResult validateJsonOutput(String json) {
        if (json == null || json.isBlank()) {
            return ValidationResult.fail("Leere JSON-Ausgabe");
        }
        if (json.length() > MAX_OUTPUT_LENGTH) {
            return ValidationResult.fail("JSON zu lang: " + json.length() + " Zeichen");
        }

        // Check for injection patterns first (security-critical)
        ValidationResult injectionCheck = checkInjections(json);
        if (!injectionCheck.valid()) return injectionCheck;

        // Check toxicity
        ValidationResult toxicityCheck = checkToxicity(json);
        if (!toxicityCheck.valid()) return toxicityCheck;

        // Check JSON structure
        return checkJsonSchema(json);
    }

    /**
     * Validate chat/tool output (plain text or structured).
     */
    public ValidationResult validateOutput(String output) {
        if (output == null || output.isBlank()) {
            return ValidationResult.ok(); // empty is OK for plain text
        }
        if (output.length() > MAX_OUTPUT_LENGTH) {
            return ValidationResult.fail("Ausgabe zu lang: " + output.length() + " Zeichen");
        }

        // Check toxicity
        ValidationResult toxicity = checkToxicity(output);
        if (!toxicity.valid()) return toxicity;

        // Check injections
        ValidationResult injection = checkInjections(output);
        if (!injection.valid()) return injection;

        return ValidationResult.ok();
    }

    // ── Checks ────────────────────────────────────────────────────

    private ValidationResult checkJsonSchema(String json) {
        // Try to find the JSON object in the text (may have markdown fences)
        String clean = json;
        int braceStart = clean.indexOf('{');
        int braceEnd = clean.lastIndexOf('}');
        if (braceStart < 0 || braceEnd <= braceStart) {
            return ValidationResult.fail("Kein gültiges JSON-Objekt gefunden");
        }
        String jsonObj = clean.substring(braceStart, braceEnd + 1);

        // Check required fields
        for (String field : REQUIRED_JSON_FIELDS) {
            if (!jsonObj.contains("\"" + field + "\"")) {
                return ValidationResult.fail("Pflichtfeld fehlt: " + field);
            }
        }

        // Check action field is non-empty
        int actionIdx = jsonObj.indexOf("\"action\"");
        if (actionIdx >= 0) {
            int colonIdx = jsonObj.indexOf(':', actionIdx);
            if (colonIdx >= 0) {
                String afterColon = jsonObj.substring(colonIdx + 1).trim();
                if (afterColon.startsWith("\"\"") || afterColon.startsWith("null")
                        || afterColon.startsWith("[]") || afterColon.startsWith("{}")) {
                    return ValidationResult.fail("Aktionsfeld ist leer");
                }
            }
        }

        // Check confidence range (0.0-1.0) if present
        int confIdx = jsonObj.indexOf("\"confidence\"");
        if (confIdx >= 0) {
            int colonIdx = jsonObj.indexOf(':', confIdx);
            if (colonIdx >= 0) {
                try {
                    String val = jsonObj.substring(colonIdx + 1).trim()
                            .replaceAll("[,}\\s].*$", "");
                    double conf = Double.parseDouble(val);
                    if (conf < 0.0 || conf > 1.0) {
                        return ValidationResult.fail("Confidence außerhalb [0.0-1.0]: " + conf);
                    }
                } catch (NumberFormatException e) {
                    // Not a simple number — might be valid, skip check
                }
            }
        }

        return ValidationResult.ok();
    }

    private ValidationResult checkToxicity(String text) {
        for (Pattern pattern : TOXIC_PATTERNS) {
            if (pattern.matcher(text).find()) {
                return ValidationResult.fail(
                        "Toxischer Inhalt gefunden: " + pattern.pattern().substring(0, Math.min(30, pattern.pattern().length())));
            }
        }
        return ValidationResult.ok();
    }

    private ValidationResult checkInjections(String text) {
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(text).find()) {
                LOG.warning("Injection pattern detected: " + pattern.pattern());
                return ValidationResult.fail(
                        "Sicherheitsrisiko: Injection-Pattern erkannt");
            }
        }
        return ValidationResult.ok();
    }

    // ── Record ────────────────────────────────────────────────────

    public record ValidationResult(boolean passed, String reason) {
        public static ValidationResult ok() {
            return new ValidationResult(true, "OK");
        }
        public static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }
        public boolean valid() { return passed; }
    }

    // ── Planner-specific validation ──────────────────────────────

    private int validationPassed = 0;
    private int validationFailed = 0;

    /**
     * Validate a planner output specifically.
     * Checks: action validity, confidence range, JSON structure, toxicity, injections.
     */
    public ValidationResult validatePlannerOutput(String action, String thought,
                                                    double confidence, String rawResponse) {
        if (action == null || action.isBlank()) {
            return ValidationResult.fail("Leere Planner-Action");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            return ValidationResult.fail("Confidence außerhalb [0.0-1.0]: " + confidence);
        }
        if (rawResponse != null && !rawResponse.isBlank()) {
            return validateJsonOutput(rawResponse);
        }
        return ValidationResult.ok();
    }

    public void recordValidation(boolean passed) {
        if (passed) validationPassed++;
        else validationFailed++;
    }

    /** Validate chat content — wrapper for HTTP API use. */
    public ValidationResult validateContent(String content, String context) {
        if (content == null || content.isBlank()) {
            return ValidationResult.ok();
        }
        return validateOutput(content);
    }

    public int validationPassed() { return validationPassed; }
    public int validationFailed() { return validationFailed; }
    public int validatedOutputs() { return validationPassed; }
    public int blockedOutputs() { return validationFailed; }
}
