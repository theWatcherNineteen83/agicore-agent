package de.metis.modules.text;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Buchstabenzähler mit Unicode-Frequenzanalyse und N-Gramm-Statistik.
 *
 * <p>Analysiert Text auf Zeichen-/Buchstaben-Ebene. Unterstützt:
 * <ul>
 *   <li>Zeichen-Frequenz (Buchstaben, Ziffern, Unicode-Kategorien)</li>
 *   <li>N-Gramme (1-gram bis 3-gram, konfigurierbar)</li>
 *   <li>Sprachdetektion via Buchstaben-Häufigkeitsprofil (DE, EN, FR, ES)</li>
 *   <li>HTML/Markdown-Stripping vor Analyse</li>
 * </ul>
 *
 * <p>Lernquelle: Java in 21 Tagen, Kapitel 5 (Arrays/Schleifen) + 19 (Streams).
 */
public final class CharCounter {

    /** Sprachprofile: erwartete relative Häufigkeiten der Top-5 Buchstaben. */
    private static final Map<String, Map<Character, Double>> LANGUAGE_PROFILES = Map.of(
            "DE", Map.of('e', 0.174, 'n', 0.098, 'i', 0.076, 's', 0.073, 'r', 0.070),
            "EN", Map.of('e', 0.127, 't', 0.091, 'a', 0.082, 'o', 0.075, 'i', 0.070),
            "FR", Map.of('e', 0.147, 'a', 0.076, 'i', 0.075, 's', 0.079, 't', 0.072),
            "ES", Map.of('e', 0.137, 'a', 0.125, 'o', 0.087, 's', 0.080, 'r', 0.069)
    );

    private final String text;
    private final String cleanText;
    private final int[] charCounts;   // Unicode code-point counts
    private Map<Character, Long> letterFreq;  // lazy
    private List<Map<String, Long>> ngrams;   // lazy: index 0=1-gram, 1=2-gram, 2=3-gram

    public CharCounter(String text) {
        this.text = Objects.requireNonNull(text, "text must not be null");
        this.cleanText = stripMarkup(text);
        this.charCounts = new int[Character.MAX_CODE_POINT + 1];
        buildCharCounts();
    }

    // ── Markup-Stripping ─────────────────────────────────────────

    /** Entfernt HTML-Tags, Markdown-Syntax und normalisiert Whitespace. */
    static String stripMarkup(String raw) {
        if (raw == null) return "";
        return raw
                // HTML-Tags
                .replaceAll("<[^>]+>", " ")
                // Markdown: **bold**, *italic*, `code`, ~~strike~~
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("\\*([^*]+)\\*", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("~~([^~]+)~~", "$1")
                // Markdown: Links [text](url), Bilder ![alt](url)
                .replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1")
                .replaceAll("!\\[([^]]*)]\\([^)]+\\)", "$1")
                // Markdown: # headers
                .replaceAll("^#{1,6}\\s+", "")
                // HTML-Entities
                .replaceAll("&[a-z]+;", " ")
                // Whitespace normalisieren
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void buildCharCounts() {
        cleanText.codePoints().forEach(cp -> {
            if (cp >= 0 && cp < charCounts.length) {
                charCounts[cp]++;
            }
        });
    }

    // ── Zeichen-Frequenz ─────────────────────────────────────────

    /** Gesamtzahl Zeichen (Code-Points). */
    public int totalChars() {
        return cleanText.codePointCount(0, cleanText.length());
    }

    /** Buchstaben (inkl. Umlaute/akzentuierte). */
    public long letterCount() {
        return letterFrequency().values().stream().mapToLong(Long::longValue).sum();
    }

    /** Ziffern. */
    public long digitCount() {
        return countByType(Character::isDigit);
    }

    /** Whitespace. */
    public long whitespaceCount() {
        return countByType(Character::isWhitespace);
    }

    /** Satzzeichen. */
    public long punctuationCount() {
        long total = totalChars();
        return total - letterCount() - digitCount() - whitespaceCount();
    }

    /**
     * Buchstaben-Frequenz: Buchstabe → Anzahl, case-insensitive, absteigend.
     * Ignoriert Unicode-Marken und Akzente via NFKD-Normalisierung.
     */
    public Map<Character, Long> letterFrequency() {
        if (letterFreq != null) return letterFreq;

        Map<Character, Long> freq = new LinkedHashMap<>();
        String normalized = Normalizer.normalize(cleanText, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", ""); // entferne diakritische Zeichen

        for (char c : normalized.toLowerCase().toCharArray()) {
            if (Character.isLetter(c)) {
                freq.merge(c, 1L, Long::sum);
            }
        }

        // Sortiere absteigend nach Häufigkeit
        letterFreq = freq.entrySet().stream()
                .sorted(Map.Entry.<Character, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
        return letterFreq;
    }

    /** Top-N häufigste Buchstaben. */
    public List<Map.Entry<Character, Long>> topLetters(int n) {
        return letterFrequency().entrySet().stream()
                .limit(n)
                .collect(Collectors.toList());
    }

    // ── N-Gramme ──────────────────────────────────────────────────

    /**
     * N-Gramm-Statistik (1-gram bis maxN-gram).
     * Index 0 = 1-gram (Einzelzeichen), 1 = 2-gram, 2 = 3-gram.
     */
    public List<Map<String, Long>> ngrams(int maxN) {
        if (ngrams != null && ngrams.size() >= maxN) return ngrams;

        ngrams = new ArrayList<>(maxN);
        String lower = cleanText.toLowerCase();
        int[] codePoints = lower.codePoints().toArray();

        for (int n = 1; n <= maxN; n++) {
            Map<String, Long> gramMap = new LinkedHashMap<>();
            for (int i = 0; i <= codePoints.length - n; i++) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < n; j++) {
                    sb.appendCodePoint(codePoints[i + j]);
                }
                gramMap.merge(sb.toString(), 1L, Long::sum);
            }
            // Sortiere absteigend
            ngrams.add(gramMap.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                            (a, b) -> a, LinkedHashMap::new)));
        }
        return ngrams;
    }

    /** Top-N häufigste K-gramme. */
    public List<Map.Entry<String, Long>> topNGrams(int k, int limit) {
        if (ngrams == null || ngrams.size() < k) ngrams(k);
        return ngrams.get(k - 1).entrySet().stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    // ── Sprachdetektion ──────────────────────────────────────────

    /**
     * Erkennt Sprache via Buchstaben-Häufigkeits-Abgleich.
     * Nutzt den euklidischen Abstand zwischen beobachteter und erwarteter Top-5-Verteilung.
     *
     * @return beste Sprachschätzung, oder "UNKNOWN" bei zu wenig Text
     */
    public String detectLanguage() {
        long letters = letterCount();
        if (letters < 20) return "UNKNOWN";

        Map<Character, Double> observed = new HashMap<>();
        for (var entry : topLetters(5)) {
            observed.put(entry.getKey(), (double) entry.getValue() / letters);
        }

        String bestLang = "UNKNOWN";
        double bestDistance = Double.MAX_VALUE;

        for (var lang : LANGUAGE_PROFILES.entrySet()) {
            double dist = 0;
            var profile = lang.getValue();
            for (char c : profile.keySet()) {
                double obs = observed.getOrDefault(c, 0.0);
                double exp = profile.get(c);
                dist += (obs - exp) * (obs - exp);
            }
            if (dist < bestDistance) {
                bestDistance = dist;
                bestLang = lang.getKey();
            }
        }
        return bestLang;
    }

    // ── Statistik-Ausgabe ────────────────────────────────────────

    /** Vollständiger Analyse-Report als String (für Logging/API). */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== CharCounter Report ===\n");
        sb.append("Gesamtzeichen: ").append(totalChars()).append("\n");
        sb.append("Buchstaben:    ").append(letterCount()).append("\n");
        sb.append("Ziffern:       ").append(digitCount()).append("\n");
        sb.append("Leerzeichen:   ").append(whitespaceCount()).append("\n");
        sb.append("Satzzeichen:   ").append(punctuationCount()).append("\n");

        sb.append("\nTop-10 Buchstaben:\n");
        for (var entry : topLetters(10)) {
            long count = entry.getValue();
            double pct = 100.0 * count / letterCount();
            sb.append(String.format("  %c: %d (%.1f%%)\n", entry.getKey(), count, pct));
        }

        sb.append("\nSprache: ").append(detectLanguage()).append("\n");

        sb.append("\nTop-5 2-gramme:\n");
        for (var entry : topNGrams(2, 5)) {
            sb.append("  \"").append(entry.getKey()).append("\": ").append(entry.getValue()).append("\n");
        }

        return sb.toString();
    }

    // ── Hilfsmethoden ────────────────────────────────────────────

    private long countByType(java.util.function.IntPredicate pred) {
        return cleanText.codePoints().filter(pred).count();
    }

    @Override
    public String toString() {
        return "CharCounter{chars=" + totalChars() + ", letters=" + letterCount()
                + ", lang=" + detectLanguage() + "}";
    }
}
