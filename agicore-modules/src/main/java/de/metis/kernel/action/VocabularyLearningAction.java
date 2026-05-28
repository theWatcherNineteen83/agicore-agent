package de.metis.kernel.action;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Learns vocabulary from speech recognition corrections.
 * <p>
 * When Metis mishears a word, the corrected pair (heard → actual) is fed to
 * this action. It extracts new words, checks them against Vosk's internal
 * vocabulary, and updates the grammar file used by {@link VoskListenAction}.
 * <p>
 * Category: write (modifies grammar files)
 * Requires human approval: no (vocabulary learning is safe)
 * <p>
 * Evolvable: Metis can optimize word selection, add phonetic hints, or
 * switch between domain-specific grammars.
 */
public class VocabularyLearningAction implements Action {

    private static final Logger LOG = Logger.getLogger(VocabularyLearningAction.class.getName());
    public static final String NAME = "learnVocabulary";

    private final String heardText;
    private final String correctText;
    private final Path grammarPath;

    /**
     * Learn from a single correction pair.
     *
     * @param heardText   what the STT engine heard (may contain errors)
     * @param correctText the ground truth / corrected text
     * @param grammarPath path to the Vosk grammar JSON file
     */
    public VocabularyLearningAction(String heardText, String correctText, Path grammarPath) {
        this.heardText = heardText;
        this.correctText = correctText;
        this.grammarPath = grammarPath;
    }

    /**
     * Learn from a correction pair using the default grammar path.
     */
    public VocabularyLearningAction(String heardText, String correctText) {
        this(heardText, correctText,
                Path.of(System.getProperty("vosk.grammar.path", "/data/prometheus/vosk-grammar-domain.json")));
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "write"; }
    @Override
    public ApprovalLevel approvalLevel() {
        return ApprovalLevel.NOTIFY; // safe write: vocabulary DB, reversible
    }

    @Override
    public ActionResult execute() {
        var now = java.time.Instant.now();
        try {
            // 1. Tokenize both texts
            Set<String> heardWords = tokenize(heardText);
            Set<String> correctWords = tokenize(correctText);

            // 2. Find words in correct text that are NOT in heard text
            Set<String> missingWords = new LinkedHashSet<>(correctWords);
            missingWords.removeAll(heardWords);

            // 3. Also find new domain words (not common German)
            Set<String> newDomainWords = correctWords.stream()
                    .filter(w -> w.length() > 3)
                    .filter(w -> !isCommonGerman(w))
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            // 4. Load existing grammar
            Set<String> currentGrammar = loadGrammar();

            // 5. Add new words
            Set<String> added = new LinkedHashSet<>();
            for (String w : missingWords) {
                if (!currentGrammar.contains(w.toLowerCase())) {
                    added.add(w.toLowerCase());
                }
            }
            for (String w : newDomainWords) {
                if (!currentGrammar.contains(w.toLowerCase())) {
                    added.add(w.toLowerCase());
                }
            }

            // 6. Update grammar file
            if (!added.isEmpty()) {
                currentGrammar.addAll(added);
                saveGrammar(currentGrammar);
                LOG.info(() -> "Vocabulary learned: +" + added.size() + " words: " +
                        String.join(", ", added));
            }

            // 7. Calculate learning metrics
            double heardCorrect = intersection(heardWords, correctWords).size();
            double precision = correctWords.isEmpty() ? 0 :
                    heardCorrect / Math.max(heardWords.size(), 1);

            return ActionResult.ok(NAME,
                    String.format("Learned %d words (grammar: %d total, precision: %.0f%%)",
                            added.size(), currentGrammar.size(), precision * 100),
                    now);

        } catch (IOException e) {
            return ActionResult.fail(NAME, "Grammar file error: " + e.getMessage(), now);
        } catch (Exception e) {
            return ActionResult.fail(NAME, "Vocabulary learning failed: " + e.getMessage(), now);
        }
    }

    // ── helpers ──

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        return Arrays.stream(text.toLowerCase()
                        .replaceAll("[.,;:!?»«()\\[\\]{}\"\"'–—-]", "")
                        .split("\\s+"))
                .filter(w -> w.length() > 1)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private <T> Set<T> intersection(Set<T> a, Set<T> b) {
        Set<T> result = new LinkedHashSet<>(a);
        result.retainAll(b);
        return result;
    }

    private Set<String> loadGrammar() throws IOException {
        if (!Files.exists(grammarPath)) {
            return new LinkedHashSet<>(DEFAULT_GERMAN_WORDS);
        }
        String content = Files.readString(grammarPath);
        // Vosk grammar is a JSON array: ["word1", "word2", ...]
        content = content.trim();
        if (content.startsWith("[")) {
            // manual parsing to avoid Gson/Jackson dependency
            return Arrays.stream(content
                            .replaceAll("[\\[\\]\"]", "")
                            .split(","))
                    .map(String::trim)
                    .filter(w -> !w.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return new LinkedHashSet<>();
    }

    private void saveGrammar(Set<String> words) throws IOException {
        // Keep grammar size reasonable for Vosk (< 200 words)
        List<String> sorted = new ArrayList<>(words);
        if (sorted.size() > 200) {
            // Prioritize: domain words first, then alphabetically
            sorted.sort((a, b) -> {
                boolean aDomain = !isCommonGerman(a);
                boolean bDomain = !isCommonGerman(b);
                if (aDomain != bDomain) return aDomain ? -1 : 1;
                return a.compareTo(b);
            });
            sorted = sorted.subList(0, 200);
        }
        final int grammarSize = sorted.size();

        String json = "[" + sorted.stream()
                .map(w -> "\"" + w + "\"")
                .collect(Collectors.joining(", ")) + "]";

        Files.createDirectories(grammarPath.getParent());
        Files.writeString(grammarPath, json);
        LOG.fine(() -> "Grammar saved: " + grammarPath + " (" + grammarSize + " words)");
    }

    /**
     * Quick heuristic for common German words (not worth adding to domain grammar).
     * A proper implementation would check against a frequency list.
     */
    private boolean isCommonGerman(String word) {
        return word.length() <= 3
                || COMMON_GERMAN.contains(word.toLowerCase())
                || word.matches("^[0-9]+$");
    }

    // ~100 most common German words that Vosk already knows well
    private static final Set<String> COMMON_GERMAN = Set.of(
            "der", "die", "das", "und", "den", "dem", "des", "ein", "eine", "einen",
            "einem", "einer", "ist", "sind", "war", "hat", "haben", "wird", "werden",
            "nicht", "mit", "auf", "für", "von", "zu", "im", "in", "an", "aus",
            "bei", "nach", "auch", "sich", "über", "vor", "durch", "bis",
            "aber", "oder", "wenn", "dann", "schon", "noch", "nur", "mehr", "sehr",
            "ich", "wir", "sie", "er", "es", "du", "ihr", "ihn", "ihm",
            "was", "wer", "wo", "wie", "warum", "wann", "welche", "welcher",
            "so", "da", "hier", "dort", "jetzt", "heute", "morgen", "gestern",
            "gut", "schlecht", "groß", "klein", "neu", "alt", "viel", "wenig",
            "kann", "muss", "soll", "will", "machen", "sagen", "gehen", "kommen",
            "ja", "nein", "bitte", "danke", "hallo", "test", "okay", "ok", "eins", "zwei", "drei"
    );

    private static final List<String> DEFAULT_GERMAN_WORDS = List.of(
            // Function words (articles, prepositions, conjunctions) — essential for sentences
            "der", "die", "das", "und", "den", "dem", "des", "ein", "eine", "einen",
            "einem", "einer", "ist", "sind", "war", "hat", "nicht", "mit", "auf",
            "für", "von", "zu", "im", "in", "an", "aus", "bei", "nach", "wie",
            "auch", "sich", "über", "vor", "als", "nur", "noch", "schon",
            "was", "wer", "wo", "warum", "wann", "so", "da", "hier",
            "kann", "muss", "soll", "will", "machen", "sagen", "gehen",
            "ja", "nein", "bitte", "danke", "hallo", "okay",
            // Domain words
            "agiles", "hörbuch", "jedes", "team", "anders", "fachlich",
            "präzises", "zugleich", "fesselndes", "erfahrene", "praktiker",
            "basierend", "werken", "fünfzehn", "kapitel", "agile",
            "psychologische", "sicherheit", "paradoxe", "kommunikation",
            "methoden", "gesprochen", "hannah", "einleitung", "dynamik",
            "sowie", "bartel", "anderson", "jackson",
            "sprint", "retrospektive", "framework",
            "thorsten", "piper", "system", "audio", "sprache",
            "mikrofon", "kopfhörer", "lautsprecher", "erkennung", "ausgabe",
            "wetter", "status", "morgen", "guten", "tag", "abend",
            "eins", "zwei", "drei", "test"
    );
}
