package de.metis.modules.speech;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Shanghainese speech learning loop: speak → listen → compare → learn.
 * <p>
 * Uses Qwen3-TTS to speak Shanghainese text, records audio, transcribes via
 * Qwen3-ASR with Wu language mode, compares the two texts, and learns new
 * vocabulary through character-level analysis.
 * <p>
 * Key differences from German SpeechLoopAction:
 * <ul>
 *   <li>Character-based tokenization (no whitespace in Chinese)</li>
 *   <li>Wu dialect language hint for ASR</li>
 *   <li>Shanghainese-specific vocabulary tracking</li>
 *   <li>serena speaker voice for natural Chinese output</li>
 * </ul>
 * <p>
 * ResourceType: CPU_HEAVY (TTS + STT both CPU-bound, ~15-40s per excerpt).
 * <p>
 * Design: Shanghainese language learning, 2026-06-05.
 */
public class ShanghaineseSpeechLoopAction implements Action {

    public static final String NAME = "shanghainese-speech-loop";
    private static final Logger LOG = Logger.getLogger(ShanghaineseSpeechLoopAction.class.getName());
    private static final int MAX_SPEAK_CHARS = 300; // Chinese text is denser than alphabetic
    private static final int LISTEN_SECONDS = 8;

    private final String text;
    private final String source;

    public ShanghaineseSpeechLoopAction(String text, String source) {
        this.text = truncate(text, MAX_SPEAK_CHARS);
        this.source = source;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        int cut = s.lastIndexOf('。', max); // Chinese period
        if (cut < max / 2) cut = s.lastIndexOf('，', max); // Chinese comma
        if (cut < max / 2) cut = s.lastIndexOf(' ', max);
        if (cut < max / 2) cut = max;
        return s.substring(0, cut + 1);
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "speech"; }

    @Override
    public ApprovalLevel approvalLevel() {
        return ApprovalLevel.NOTIFY;
    }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        List<String> log = new ArrayList<>();
        int wordsLearned = 0;

        try {
            // ── Phase 1: Speak via Qwen3-TTS ──
            log.add("SPEAK: \"" + text.substring(0, Math.min(60, text.length())) + "...\"");
            QwenTtsAction tts = new QwenTtsAction(text, "serena",
                    "Speak naturally in Chinese Wu dialect Shanghainese style.");
            ActionResult ttsResult = tts.execute();

            if (!ttsResult.success()) {
                log.add("TTS FAILED: " + ttsResult.error());
                return ActionResult.fail(NAME, String.join(" | ", log), start);
            }
            log.add("TTS OK: " + ttsResult.body());

            // Extract WAV file path from TTS result
            String ttsBody = ttsResult.body();
            String wavPath = extractWavPath(ttsBody);
            if (wavPath == null) {
                log.add("NO WAV PATH in TTS result");
                return ActionResult.fail(NAME, String.join(" | ", log), start);
            }

            // ── Phase 2: Transcribe via Qwen3-ASR ──
            // We need to record from microphone. For now, use the VoskListenAction
            // as a bridge, but we can also play the WAV and listen.
            // Simplified: just transcribe the generated WAV directly
            log.add("STT: transcribing generated WAV...");
            Thread.sleep(500);

            QwenAsrSttAction stt = new QwenAsrSttAction(Path.of(wavPath), "Wu");
            ActionResult sttResult = stt.execute();

            if (!sttResult.success()) {
                log.add("STT FAILED: " + sttResult.error());
                return ActionResult.fail(NAME, String.join(" | ", log), start);
            }

            String sttBody = sttResult.body();
            String heard = extractHeardText(sttBody);
            log.add("HEARD: \"" + (heard != null ? truncate(heard, 120) : "(null)") + "\"");

            // ── Phase 3: Learn Shanghainese vocabulary ──
            if (heard != null && !heard.isBlank()) {
                ShanghaineseVocabularyAction vocab = new ShanghaineseVocabularyAction(heard, text);
                ActionResult vocabResult = vocab.execute();

                if (vocabResult.success()) {
                    log.add("VOCAB: " + vocabResult.body());
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

            // ── Phase 4: Character similarity ──
            double similarity = computeCharacterSimilarity(text, heard);
            log.add(String.format("SIM=%.0f%%", similarity * 100));

            long elapsedMs = Duration.between(start, Instant.now()).toMillis();
            final int learned = wordsLearned;
            LOG.info(() -> "ShanghaineseSpeechLoop [" + source + "]: " + learned
                    + " chars learned, " + String.format("%.0f%%", similarity * 100)
                    + " similarity, " + elapsedMs + "ms");

            return ActionResult.ok(NAME,
                    String.format("ShanghaineseLoop: +%d chars, %.0f%% similar (%dms) | %s",
                            wordsLearned, similarity * 100, elapsedMs,
                            String.join(" | ", log.subList(Math.max(0, log.size() - 3), log.size()))),
                    start);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.fail(NAME, "Interrupted", start);
        } catch (Exception e) {
            log.add("ERROR: " + e.getMessage());
            LOG.warning("ShanghaineseSpeechLoop failed: " + e.getMessage());
            return ActionResult.fail(NAME, String.join(" | ", log), start);
        }
    }

    /**
     * Character-level Jaccard similarity for Chinese text.
     */
    static double computeCharacterSimilarity(String spoken, String heard) {
        if (spoken == null || heard == null) return 0;
        Set<Integer> spokenChars = toCodePoints(spoken);
        Set<Integer> heardChars = toCodePoints(heard);
        if (spokenChars.isEmpty()) return 0;

        Set<Integer> intersection = new LinkedHashSet<>(spokenChars);
        intersection.retainAll(heardChars);

        Set<Integer> union = new LinkedHashSet<>(spokenChars);
        union.addAll(heardChars);

        return (double) intersection.size() / union.size();
    }

    static Set<Integer> toCodePoints(String s) {
        Set<Integer> result = new LinkedHashSet<>();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            // Skip spaces, punctuation
            if (Character.isLetterOrDigit(cp) || Character.isIdeographic(cp)) {
                result.add(cp);
            }
            i += Character.charCount(cp);
        }
        return result;
    }

    // ── result parsing helpers ──

    private static String extractWavPath(String ttsBody) {
        if (ttsBody == null) return null;
        // Result format: "TTS: ...bytes, ...s (...) → /tmp/metis-tts-qwen-XXXX.wav | speaker=serena"
        int arrow = ttsBody.indexOf(" → ");
        if (arrow < 0) return null;
        int pipe = ttsBody.indexOf(" | ", arrow);
        String path = pipe > arrow ? ttsBody.substring(arrow + 3, pipe).trim()
                                   : ttsBody.substring(arrow + 3).trim();
        return path;
    }

    private static String extractHeardText(String sttBody) {
        if (sttBody == null) return null;
        // Result format: "STT: <text> | lang=Wu"
        if (sttBody.startsWith("STT: ")) {
            String rest = sttBody.substring(5);
            int pipe = rest.indexOf(" | lang=");
            if (pipe > 0) return rest.substring(0, pipe).trim();
            return rest.trim();
        }
        return sttBody;
    }
}
