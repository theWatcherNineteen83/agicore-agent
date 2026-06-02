package de.metis.kernel.safety;

import java.util.*;
import java.util.regex.Pattern;
import java.util.logging.Logger;

/**
 * Prüft deutschsprachige LLM-Ausgaben auf Code-Switching, Formfehler und Qualität.
 * <p>
 * Checks:
 * <ul>
 *   <li><b>Code-Switching:</b> detektiert ungewolltes Englisch (Wort-Verhältnis >30%)</li>
 *   <li><b>Umlaut-Fallbacks:</b> ae→ä, oe→ö, ue→ü, ss→ß (common ASCII replacements)</li>
 *   <li><b>Großschreibung:</b> flaggt fehlende Satzanfangs-Großschreibung</li>
 *   <li><b>Sie/Du-Konsistenz:</b> mischt nicht formelle und informelle Anrede</li>
 *   <li><b>Deutsche Idiome:</b> erkennt Anglizismen, die auf Deutsch seltsam wirken</li>
 * </ul>
 * <p>
 * Kein vollständiger Grammatik-Checker — nur schnelle, deterministische Pattern-Matches.
 * Soll LLM-Antworten nicht blockieren, sondern Metis Awareness geben (logging + Beliefs).
 */
public class GermanLanguageGuard {

    private static final Logger LOG = Logger.getLogger(GermanLanguageGuard.class.getName());

    // ── Thresholds ───────────────────────────────────────────────

    /** Wenn >30% der Wörter englisch sind, ist das Code-Switching. */
    private static final double ENGLISH_WORD_RATIO_THRESHOLD = 0.30;

    /** Englische Wörter, die auf Deutsch NICHT zählen (Lehnwörter). */
    private static final Set<String> GERMAN_LOANWORDS = Set.of(
            "computer", "internet", "server", "api", "docker", "proxy", "cache",
            "token", "json", "http", "url", "cloud", "app", "bug", "fix", "patch",
            "code", "script", "stream", "webcam", "chat", "bot", "agent", "model",
            "prompt", "linux", "java", "python", "git", "log", "test",
            "watchdog", "kernel", "tool", "framework", "plugin", "pipeline",
            "gpu", "cpu", "vram", "ram", "ssd", "status", "list", "map", "set"
    );

    /** Häufige englische Wörter, die auf Code-Switching hindeuten. */
    private static final Pattern ENGLISH_WORD = Pattern.compile(
            "(?i)\\b(the|is|are|was|were|been|have|has|had|do|does|did|will|would|"
                    + "could|should|may|might|shall|can|this|that|these|those|"
                    + "and|but|or|for|with|without|about|because|from|into|over|under|"
                    + "just|very|really|actually|pretty|quite|rather|probably|maybe|"
                    + "thing|stuff|like|well|good|bad|nice|great|awesome|cool|"
                    + "maybe|also|too|either|neither|however|therefore|thus|hence|"
                    + "though|although|while|whereas|since|because|unless|until)\\b");

    // ── ANGLIZISMEN: englische Formulierungen, die auf Deutsch seltsam klingen ──
    private static final Map<String, String> ANGLIZISMUS_MAP = Map.ofEntries(
            Map.entry("Sinn machen", "Sinn ergeben"),
            Map.entry("sinn machen", "Sinn ergeben"),
            Map.entry("in 2026", "im Jahr 2026"),
            Map.entry("ich erinnere", "ich erinnere mich"),
            Map.entry("realisieren", "erkennen / umsetzen"),
            Map.entry("eventuell", "möglicherweise"),
            Map.entry("schlussendlich", "letztendlich / schließlich")
    );

    // ── Umlaut-Fallback-Patterns (ae→ä, etc.) ───────────────────
    private static final Pattern UMLAUT_FALLBACK = Pattern.compile(
            "\\b[a-z]*ae[a-z]*\\b|\\b[a-z]*oe[a-z]*\\b|\\b[a-z]*ue[a-z]*\\b",
            Pattern.CASE_INSENSITIVE);

    /** Wörter, bei denen ae/oe/ue korrekt sind (kein Umlaut). */
    private static final Set<String> VALID_AE_OE_UE = Set.of(
            "Aerosol", "Poet", "Poesie", "Koeffizient", "Event", "Eventuell",
            "eventuell", "User", "Query", "Sequenz", "Frequenz", "Equipment",
            "Client", "Recipe", "Request", "Response");

    // ── Sie/Du-Konsistenz ────────────────────────────────────────
    private static final Pattern SIE_PATTERN = Pattern.compile(
            "\\b(Sie|Ihnen|Ihr|Ihre|Ihren|Ihrer|Ihrem)\\b");
    private static final Pattern DU_PATTERN = Pattern.compile(
            "\\b(du|dich|dir|dein|deine|deinen|deiner|deinem)\\b");

    // ── Großschreibung ───────────────────────────────────────────
    private static final Pattern SENTENCE_START = Pattern.compile(
            "(?:^|[.!?]\\s+)([a-zäöü])", Pattern.MULTILINE);

    // ── Public API ────────────────────────────────────────────────

    /**
     * Vollständige Deutsch-Prüfung einer LLM-Ausgabe.
     *
     * @param text  der zu prüfende Text
     * @param allowEnglish wenn true, wird Code-Switching ignoriert (z.B. bei technischen Antworten)
     * @return Ergebnis mit Score und Liste der Findings
     */
    public GermanCheckResult check(String text, boolean allowEnglish) {
        if (text == null || text.isBlank()) {
            return GermanCheckResult.OK;
        }

        List<String> findings = new ArrayList<>();
        double score = 1.0;

        // 1. Code-Switching (English detection)
        if (!allowEnglish) {
            double englishRatio = englishWordRatio(text);
            if (englishRatio > ENGLISH_WORD_RATIO_THRESHOLD) {
                findings.add("Code-Switching: " + String.format("%.0f%%", englishRatio * 100)
                        + " englische Wörter (Schwelle: " + String.format("%.0f%%",
                        ENGLISH_WORD_RATIO_THRESHOLD * 100) + ")");
                score -= 0.25;
            } else if (englishRatio > 0.10) {
                findings.add("Leichtes Code-Switching: " + String.format("%.0f%%",
                        englishRatio * 100) + " englische Wörter");
                score -= 0.10;
            }
        }

        // 2. Umlaut-Fallbacks (nur flaggen, kein Scoring-Abzug)
        int umlautFallbacks = countUmlautFallbacks(text);
        if (umlautFallbacks > 0) {
            findings.add("Umlaut-Fallbacks: " + umlautFallbacks
                    + " ae/oe/ue-Ersetzungen gefunden (z.B. 'waehrend' statt 'während')");
        }

        // 3. Sie/Du-Mischung
        boolean hasSie = SIE_PATTERN.matcher(text).find();
        boolean hasDu = DU_PATTERN.matcher(text).find();
        if (hasSie && hasDu) {
            findings.add("Anrede gemischt: sowohl 'Sie' als auch 'du' im selben Text");
            score -= 0.15;
        }

        // 4. Anglizismen
        List<String> anglizismen = findAnglizismen(text);
        if (!anglizismen.isEmpty()) {
            findings.add("Anglizismen: " + String.join(", ", anglizismen));
            score -= 0.05 * Math.min(anglizismen.size(), 3);
        }

        // 5. Satzanfangs-Großschreibung
        int lowerCaseStarts = countLowerCaseSentenceStarts(text);
        if (lowerCaseStarts > 0) {
            findings.add("Kleinschreibung am Satzanfang: " + lowerCaseStarts + "×");
            score -= 0.05 * Math.min(lowerCaseStarts, 3);
        }

        score = Math.max(0.0, score);
        return new GermanCheckResult(score, List.copyOf(findings));
    }

    /**
     * Kurzcheck ohne English-Ratio (für technische Outputs).
     */
    public GermanCheckResult check(String text) {
        return check(text, true);
    }

    // ── Private Checks ────────────────────────────────────────────

    private double englishWordRatio(String text) {
        String[] words = text.split("[\\s,.!?;:()\\[\\]\"'\\-]+");
        if (words.length == 0) return 0.0;

        int englishCount = 0;
        int totalCount = 0;

        for (String word : words) {
            if (word.isBlank() || word.length() <= 1) continue;
            totalCount++;

            String lower = word.toLowerCase().replaceAll("[^a-zäöüß]", "");
            if (lower.isEmpty()) continue;

            // Skip German loanwords
            if (GERMAN_LOANWORDS.contains(lower)) continue;

            // Check if it matches known English patterns
            if (ENGLISH_WORD.matcher(word).find()) {
                englishCount++;
                continue;
            }

            // Quick heuristic: words with 'th', 'ght', 'tion' are likely English
            if (lower.contains("th") || lower.contains("ght") || lower.endsWith("tion")
                    || lower.endsWith("ing") || lower.endsWith("ed") || lower.endsWith("ly")
                    || lower.endsWith("ous") || lower.startsWith("un") || lower.startsWith("re")) {

                // But skip German words that match (e.g., "Richtung", "Bedienung")
                if (lower.contains("ch") || lower.contains("sch") || lower.contains("ei")
                        || lower.contains("ie") || lower.contains("eu") || lower.contains("äu")
                        || lower.contains("ö") || lower.contains("ü") || lower.contains("ß")) {
                    continue; // Probably German
                }

                englishCount++;
            }
        }

        return totalCount > 0 ? (double) englishCount / totalCount : 0.0;
    }

    private int countUmlautFallbacks(String text) {
        int count = 0;
        String[] words = text.split("[\\s,.!?;:()\\[\\]\"']+");
        for (String word : words) {
            if (word.length() < 3) continue;

            String lower = word.toLowerCase();
            // Skip known valid ae/oe/ue words
            boolean isValid = false;
            for (String valid : VALID_AE_OE_UE) {
                if (lower.equals(valid.toLowerCase())) {
                    isValid = true;
                    break;
                }
            }
            if (isValid) continue;

            // Count words with ae/oe/ue that should be ä/ö/ü
            if ((lower.contains("ae") || lower.contains("oe") || lower.contains("ue"))
                    && UMLAUT_FALLBACK.matcher(word).find()) {
                // Exclude very short or obvious English words
                if (lower.length() <= 4) continue;
                if (lower.startsWith("que") || lower.startsWith("req")) continue;
                count++;
            }
        }
        return count;
    }

    private List<String> findAnglizismen(String text) {
        List<String> found = new ArrayList<>();
        for (var entry : ANGLIZISMUS_MAP.entrySet()) {
            if (text.contains(entry.getKey())) {
                found.add(entry.getKey() + " → " + entry.getValue());
            }
        }
        return found;
    }

    private int countLowerCaseSentenceStarts(String text) {
        int count = 0;
        var matcher = SENTENCE_START.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    // ── Result Record ─────────────────────────────────────────────

    /**
     * Ergebnis einer Deutsch-Prüfung.
     *
     * @param score    1.0 = perfektes Deutsch, 0.0 = sehr schlecht
     * @param findings Liste der gefundenen Probleme (leer wenn score=1.0)
     */
    public record GermanCheckResult(double score, List<String> findings) {

        public static final GermanCheckResult OK = new GermanCheckResult(1.0, List.of());

        /** Score < 0.5 als "nicht akzeptabel" für UI/User-facing Outputs. */
        public boolean isAcceptable() {
            return score >= 0.5;
        }

        /** Score >= 0.8 als "gut genug" ohne Verbesserungsvorschlag. */
        public boolean isGood() {
            return score >= 0.8;
        }

        @Override
        public String toString() {
            if (score >= 1.0) return "Deutsch: perfekt ✅";
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Deutsch: %.0f%%", score * 100));
            if (!findings.isEmpty()) {
                sb.append(" — ").append(String.join("; ", findings));
            }
            return sb.toString();
        }
    }
}
