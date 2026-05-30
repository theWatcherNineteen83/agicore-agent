package de.metis.modules.speech;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;
import de.metis.kernel.action.VocabularyLearningAction;
import de.metis.kernel.action.VoskListenAction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Speech learning loop: speak → listen → compare → learn.
 * <p>
 * Reads a text excerpt (Wikipedia article), speaks it via Piper TTS,
 * listens back via Vosk STT, compares the two texts word-by-word,
 * and feeds misheard words to {@link VocabularyLearningAction}.
 * <p>
 * ResourceType: CPU_HEAVY (TTS+STT both CPU-bound, ~15-30s per article).
 * ServiceClass: STANDARD.
 * <p>
 * Only ~5% of articles get speech-loop processing — controlled by the scheduler.
 */
public class SpeechLoopAction implements Action {

    public static final String NAME = "speech-loop";
    private static final Logger LOG = Logger.getLogger(SpeechLoopAction.class.getName());
    private static final Random RANDOM = new Random();

    /** Maximum characters to speak (Piper handles ~500 chars comfortably). */
    private static final int MAX_SPEAK_CHARS = 500;

    /** Listen duration in seconds. */
    private static final int LISTEN_SECONDS = 8;

    private final String text;
    private final String source;
    private final Path grammarPath;

    /**
     * @param text        excerpt to speak (first ~500 chars used)
     * @param source      article title for logging
     * @param grammarPath Vosk grammar JSON path
     */
    public SpeechLoopAction(String text, String source, Path grammarPath) {
        this.text = truncate(text, MAX_SPEAK_CHARS);
        this.source = source;
        this.grammarPath = grammarPath;
    }

    public SpeechLoopAction(String text, String source) {
        this(text, source, Path.of(System.getProperty("vosk.grammar.path",
                "/data/prometheus/vosk-grammar-domain.json")));
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        // Truncate at last sentence boundary
        int cut = s.lastIndexOf('.', max);
        if (cut < max / 2) cut = s.lastIndexOf(' ', max);
        if (cut < max / 2) cut = max;
        return s.substring(0, cut + 1);
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "speech"; }

    @Override
    public ApprovalLevel approvalLevel() {
        return ApprovalLevel.NOTIFY; // safe: only reads mic, writes vocabulary
    }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        List<String> log = new ArrayList<>();
        int wordsLearned = 0;

        try {
            // ── Phase 1: Speak via Piper TTS ──
            log.add("SPEAK: \"" + text.substring(0, Math.min(60, text.length())) + "...\"");
            PiperTtsAction tts = new PiperTtsAction(text);
            ActionResult ttsResult = tts.execute();

            if (!ttsResult.success()) {
                log.add("TTS FAILED: " + ttsResult.body());
                return ActionResult.fail(NAME, String.join(" | ", log), start);
            }
            log.add("TTS OK: " + ttsResult.body());
            // Small gap for audio playback to finish
            Thread.sleep(500);

            // ── Phase 2: Listen via Vosk STT ──
            log.add("LISTEN: " + LISTEN_SECONDS + "s");
            VoskListenAction stt = new VoskListenAction(LISTEN_SECONDS);
            ActionResult sttResult = stt.execute();

            if (!sttResult.success()) {
                log.add("STT FAILED: " + sttResult.body());
                return ActionResult.fail(NAME, String.join(" | ", log), start);
            }
            String heard = sttResult.body();
            log.add("HEARD: \"" + truncate(heard, 120) + "\"");

            // ── Phase 3: Compare & learn vocabulary ──
            if (heard != null && !heard.isBlank() && !heard.equals("(silence)")) {
                VocabularyLearningAction vocab = new VocabularyLearningAction(heard, text, grammarPath);
                ActionResult vocabResult = vocab.execute();

                if (vocabResult.success()) {
                    log.add("VOCAB: " + vocabResult.body());
                    // Extract word count from result
                    String body = vocabResult.body();
                    int idx = body.indexOf("Learned ");
                    if (idx >= 0) {
                        String numStr = body.substring(idx + 8, body.indexOf(" words", idx));
                        try { wordsLearned = Integer.parseInt(numStr); } catch (NumberFormatException ignored) {}
                    }
                }
            } else {
                log.add("STT SILENCE — no vocabulary learning");
            }

            // ── Phase 4: Word similarity score ──
            double similarity = computeSimilarity(text, heard);
            log.add(String.format("SIM=%.0f%%", similarity * 100));

            long elapsedMs = Duration.between(start, Instant.now()).toMillis();
            final int learned = wordsLearned;
            LOG.info(() -> "SpeechLoop [" + source + "]: " + learned + " words learned, "
                    + String.format("%.0f%%", similarity * 100) + " similarity, " + elapsedMs + "ms");

            return ActionResult.ok(NAME,
                    String.format("SpeechLoop: +%d words, %.0f%% similar (%dms) | %s",
                            wordsLearned, similarity * 100, elapsedMs,
                            String.join(" | ", log.subList(Math.max(0, log.size() - 3), log.size()))),
                    start);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.fail(NAME, "Interrupted", start);
        } catch (Exception e) {
            log.add("ERROR: " + e.getMessage());
            return ActionResult.fail(NAME, String.join(" | ", log), start);
        }
    }

    /**
     * Compute word-level similarity between spoken and heard text.
     * Uses Jaccard similarity on tokenized word sets.
     */
    static double computeSimilarity(String spoken, String heard) {
        if (spoken == null || heard == null) return 0;
        Set<String> spokenWords = tokenize(spoken);
        Set<String> heardWords = tokenize(heard);
        if (spokenWords.isEmpty()) return 0;

        Set<String> intersection = new LinkedHashSet<>(spokenWords);
        intersection.retainAll(heardWords);

        Set<String> union = new LinkedHashSet<>(spokenWords);
        union.addAll(heardWords);

        return (double) intersection.size() / union.size();
    }

    private static Set<String> tokenize(String text) {
        return new LinkedHashSet<>(Arrays.asList(
                text.toLowerCase()
                        .replaceAll("[.,;:!?»«()\\[\\]{}\"\"'–—-]", "")
                        .split("\\s+")));
    }
}
