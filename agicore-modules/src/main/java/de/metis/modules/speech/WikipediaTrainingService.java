package de.metis.modules.speech;

import de.metis.kernel.action.VocabularyLearningAction;
import de.metis.kernel.goal.GoalManager;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.vosk.LibVosk;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Wikipedia article processing with integrated speech training.
 * <p>
 * Pipeline per article:
 * <ol>
 *   <li>Read article → extract key sentences (first 2 paragraphs)</li>
 *   <li>Every N articles: TTS speak → STT listen → compare → learn corrections</li>
 *   <li>Track progress via JSON state file (resumable, persistent)</li>
 * </ol>
 * <p>
 * Learning loop: Speak → Hear → Compare → Improve
 * <ul>
 *   <li><b>Speak:</b> Piper TTS generates audio from text</li>
 *   <li><b>Hear:</b> Whisper STT transcribes audio back to text</li>
 *   <li><b>Compare:</b> Word Error Rate + extract specific corrections</li>
 *   <li><b>Improve:</b> VocabularyLearningAction adds corrections to Vosk grammar</li>
 * </ul>
 */
public class WikipediaTrainingService {

    private static final Logger LOG = Logger.getLogger(WikipediaTrainingService.class.getName());

    // Training configuration
    private final Path articleDir;
    private final int speechInterval;   // run speech training every N articles
    private final int maxSentencesPerArticle;  // sentences to extract per article

    // State tracking
    private final Path stateFile;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread trainingThread;
    private TrainingState state;

    // Speech pipeline references
    private final GoalManager goalManager;

    /**
     * @param articleDir  directory with Wikipedia .txt files
     * @param goalManager Metis goal manager for submitting learning goals
     * @param statePath   persistent path for training state JSON (e.g., /home/prometheus/metis/wiki-training-state.json)
     */
    public WikipediaTrainingService(Path articleDir, GoalManager goalManager, Path statePath) {
        this.articleDir = articleDir;
        this.goalManager = goalManager;
        this.speechInterval = 2;          // speech train every 2 articles
        this.maxSentencesPerArticle = 3;  // extract 3 key sentences
        this.stateFile = statePath;
    }

    // ── Lifecycle ──────────────────────────────────────────────────

    public synchronized void start() {
        if (running.get()) return;
        loadState();

        running.set(true);
        trainingThread = new Thread(this::runTraining, "metis-wiki-train");
        trainingThread.setDaemon(true);
        trainingThread.start();
        LOG.info("WikipediaTrainingService started — " + state.remainingArticles()
                + " articles remaining, speech every " + speechInterval);
    }

    public synchronized void stop() {
        running.set(false);
        if (trainingThread != null) {
            trainingThread.interrupt();
            try { trainingThread.join(5000); } catch (InterruptedException ignored) {}
        }
        saveState();
        LOG.info("WikipediaTrainingService stopped — "
                + state.processedCount + " articles, " + state.wordsLearned + " words learned");
    }

    public boolean isRunning() { return running.get(); }
    public TrainingState currentState() { return state; }

    // ── Main Loop ─────────────────────────────────────────────────

    private void runTraining() {
        try {
            File[] files = articleDir.toFile().listFiles((d, n) -> n.endsWith(".txt"));
            if (files == null || files.length == 0) {
                LOG.warning("No Wikipedia articles found in " + articleDir);
                return;
            }

            Arrays.sort(files, Comparator.comparing(File::getName));

            for (File file : files) {
                if (!running.get()) break;

                String title = file.getName().replace(".txt", "").replace('_', ' ');

                // Skip already processed articles
                if (state.processedTitles.contains(title)) {
                    continue;
                }

                LOG.info("Processing article: " + title);
                processArticle(file, title);

                state.processedTitles.add(title);
                state.processedCount++;

                // Speech training every N articles
                if (state.processedCount % speechInterval == 0) {
                    LOG.info("Speech training cycle after " + state.processedCount + " articles");
                    runSpeechTraining();
                }

                saveState();

                // Small pause between articles
                Thread.sleep(2000);
            }

            LOG.info("All articles processed! Total: " + state.processedCount
                    + " articles, " + state.wordsLearned + " words learned");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.warning("Training error: " + e.getMessage());
        } finally {
            saveState();
        }
    }

    // ── Article Processing ────────────────────────────────────────

    private void processArticle(File file, String title) throws IOException {
        String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);

        // Extract key sentences for speech training
        List<String> sentences = extractKeySentences(text);
        state.pendingSentences.addAll(sentences);

        // Store article knowledge as a Metis goal
        if (!sentences.isEmpty()) {
            String summary = sentences.get(0);
            goalManager.add("Wiki gelernt: " + title + " — " + summary,
                    20, 0.4, 1);
        }

        LOG.fine("Article " + title + ": " + sentences.size() + " sentences extracted");
    }

    /**
     * Extract key sentences from article text.
     * Takes first 2 paragraphs, splits into sentences, returns up to maxSentencesPerArticle.
     */
    private List<String> extractKeySentences(String text) {
        List<String> result = new ArrayList<>();

        // Get first 2 paragraphs
        String[] paragraphs = text.split("\n\n");
        int paraCount = 0;
        for (String p : paragraphs) {
            String clean = p.trim().replaceAll("\\s+", " ");
            if (clean.length() < 30) continue;
            if (clean.startsWith("Datei:") || clean.startsWith("Kategorie:")
                    || clean.startsWith("Siehe auch") || clean.startsWith("Weblinks")) continue;

            // Split into sentences
            String[] sentences = clean.split("(?<=[.!?])\\s+");
            for (String s : sentences) {
                String trimmed = s.trim();
                if (trimmed.length() > 30 && trimmed.length() < 200) {
                    result.add(trimmed);
                    if (result.size() >= maxSentencesPerArticle) return result;
                }
            }

            paraCount++;
            if (paraCount >= 2) break;
        }

        return result;
    }

    // ── Speech Training (Speak → Hear → Compare → Improve) ─────────

    // Piper TTS + Vosk STT (both local, no external binaries except piper)
    private static final String PIPER_BIN = "/usr/local/bin/piper";
    private static final String PIPER_MODEL = "/home/prometheus/piper-voices/de_DE-thorsten-medium.onnx";
    private static final String VOSK_MODEL_PATH = "/data/prometheus/vosk-model-de/vosk-model-small-de-0.15";
    private static final float VOSK_SAMPLE_RATE = 16000f;
    private static volatile Model voskModel;  // lazy-loaded singleton

    private void runSpeechTraining() {
        List<String> sentences = new ArrayList<>(state.pendingSentences);
        state.pendingSentences.clear();

        if (sentences.isEmpty()) return;

        int trainedThisCycle = 0;

        for (String original : sentences) {
            if (!running.get()) break;

            try {
                // 1. SPEAK: Piper TTS → WAV file
                Path wavFile = Files.createTempFile("metis-speech-", ".wav");

                ProcessBuilder ttsPb = new ProcessBuilder(
                        PIPER_BIN, "--model", PIPER_MODEL,
                        "--output_file", wavFile.toString()
                );
                ttsPb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process ttsProc = ttsPb.start();
                try (var stdin = ttsProc.getOutputStream()) {
                    stdin.write(original.getBytes(StandardCharsets.UTF_8));
                }

                boolean ttsOk = ttsProc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                if (!ttsOk || ttsProc.exitValue() != 0) {
                    LOG.fine("Piper TTS failed for sentence, skipping");
                    try { Files.deleteIfExists(wavFile); } catch (IOException ignored) {}
                    continue;
                }

                // 2. HEAR: Vosk STT directly from WAV file (reliable, Java-native)
                String heard = "";
                try {
                    byte[] pcm16k = resampleWavTo16kHz(wavFile);
                    if (pcm16k != null && pcm16k.length > 0) {
                        Model m = getVoskModel();
                        try (Recognizer rec = new Recognizer(m, VOSK_SAMPLE_RATE)) {
                            rec.setWords(true);
                            if (rec.acceptWaveForm(pcm16k, pcm16k.length)) {
                                String resultJson = rec.getResult();
                                heard = extractTextFromVoskResult(resultJson);
                            } else {
                                String partial = rec.getPartialResult();
                                heard = extractPartialFromVoskResult(partial);
                            }
                        }
                    }
                } catch (Exception voskEx) {
                    LOG.warning("Vosk STT failed: " + voskEx.getMessage());
                }

                // Cleanup temp file
                try { Files.deleteIfExists(wavFile); } catch (IOException ignored) {}

                if (heard.isEmpty()) {
                    LOG.fine("Vosk produced no output for: " + original.substring(0, Math.min(40, original.length())));
                    continue;
                }

                // 3. COMPARE: Calculate word similarity
                double similarity = wordSimilarity(original, heard);
                LOG.info("Speech: \"" + original.substring(0, Math.min(60, original.length()))
                        + "\" → heard \"" + heard + "\" (" + String.format("%.0f%%", similarity * 100) + ")");

                // 4. IMPROVE: Learn if similarity < 95% (lenient threshold for synthetic speech)
                if (similarity < 0.95) {
                    try {
                        var vocabAction = new VocabularyLearningAction(heard, original);
                        vocabAction.execute();
                    } catch (Exception vocabEx) {
                        LOG.fine("VocabularyLearningAction failed (non-critical): " + vocabEx.getMessage());
                    }
                    state.wordsLearned++;
                    trainedThisCycle++;
                    goalManager.add("Sprachtraining: \"" + heard + "\" → \"" + original + "\"",
                        30, 0.5, 1);
                }

                // Small pause between sentences
                Thread.sleep(1000);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.warning("Speech training sentence error: " + e.getMessage());
            }
        }

        LOG.info("Speech cycle done: " + trainedThisCycle + " corrections from "
                + sentences.size() + " sentences (total words learned: " + state.wordsLearned + ")");

        saveState();
    }

    /**
     * Calculate word-level similarity between original and heard text.
     * Returns 0.0 (completely different) to 1.0 (identical).
     */
    static double wordSimilarity(String original, String heard) {
        if (original == null || heard == null) return 0.0;

        String[] origWords = original.toLowerCase()
                .replaceAll("[^a-zäöüß0-9 ]", "")
                .split("\\s+");
        String[] heardWords = heard.toLowerCase()
                .replaceAll("[^a-zäöüß0-9 ]", "")
                .split("\\s+");

        if (origWords.length == 0) return 0.0;

        Set<String> origSet = new HashSet<>(Arrays.asList(origWords));
        Set<String> heardSet = new HashSet<>(Arrays.asList(heardWords));

        Set<String> intersection = new HashSet<>(origSet);
        intersection.retainAll(heardSet);

        return (double) intersection.size() / origSet.size();
    }

    // ── Vosk Helpers ─────────────────────────────────────────────

    private static synchronized Model getVoskModel() throws IOException {
        if (voskModel == null) {
            LibVosk.vosk_set_log_level(-1);
            voskModel = new Model(VOSK_MODEL_PATH);
            LOG.info("Vosk model loaded: " + VOSK_MODEL_PATH);
        }
        return voskModel;
    }

    /**
     * Reads a WAV file (22050 Hz from Piper), resamples to 16kHz mono PCM for Vosk.
     */
    private static byte[] resampleWavTo16kHz(Path wavFile) throws Exception {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(wavFile.toFile())) {
            AudioFormat srcFormat = ais.getFormat();
            AudioFormat targetFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    VOSK_SAMPLE_RATE,
                    16,
                    1,
                    2,
                    VOSK_SAMPLE_RATE,
                    false);

            // If format already matches, just read bytes
            if (AudioSystem.isConversionSupported(targetFormat, srcFormat)) {
                try (AudioInputStream converted = AudioSystem.getAudioInputStream(targetFormat, ais)) {
                    return converted.readAllBytes();
                }
            }

            // Manual resampling fallback
            byte[] srcBytes = ais.readAllBytes();
            return linearResample(srcBytes, srcFormat, VOSK_SAMPLE_RATE);
        }
    }

    /**
     * Simple linear resampling. For production use, consider a proper resampling library.
     */
    private static byte[] linearResample(byte[] srcBytes, AudioFormat srcFormat, float targetRate) {
        int srcChannels = srcFormat.getChannels();
        int srcSampleSize = srcFormat.getSampleSizeInBits() / 8;
        float srcRate = srcFormat.getSampleRate();
        double ratio = targetRate / srcRate;

        int srcFrames = srcBytes.length / (srcChannels * srcSampleSize);
        int dstFrames = (int) (srcFrames * ratio);
        byte[] dstBytes = new byte[dstFrames * 2]; // 16-bit mono output

        ByteBuffer srcBuf = ByteBuffer.wrap(srcBytes).order(
                srcFormat.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        ByteBuffer dstBuf = ByteBuffer.wrap(dstBytes).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < dstFrames; i++) {
            double srcPos = i / ratio;
            int srcIdx = (int) srcPos;
            double frac = srcPos - srcIdx;

            // Read sample (handle multi-channel by taking first channel only if needed)
            int bytePos = srcIdx * srcChannels * srcSampleSize;
            if (bytePos + srcSampleSize > srcBytes.length) break;

            short sample;
            if (srcSampleSize == 2) {
                sample = srcBuf.getShort(bytePos);
            } else {
                sample = (short) (srcBuf.get(bytePos) << 8);
            }

            // Optional linear interpolation
            if (frac > 0.01 && bytePos + srcChannels * srcSampleSize + srcSampleSize <= srcBytes.length) {
                short nextSample = srcBuf.getShort(bytePos + srcChannels * srcSampleSize);
                sample = (short) (sample + frac * (nextSample - sample));
            }

            dstBuf.putShort(sample);
        }

        return dstBytes;
    }

    static String extractTextFromVoskResult(String json) {
        if (json == null || json.isBlank()) return "";
        // Vosk result: {"result": [{"word": "hallo", ...}], "text": "hallo welt"}
        int textIdx = json.indexOf("\"text\" : \"");
        if (textIdx < 0) textIdx = json.indexOf("\"text\":\"");
        if (textIdx >= 0) {
            int start = json.indexOf('"', textIdx + 8) + 1;
            int end = json.indexOf('"', start);
            if (start > 0 && end > start) return json.substring(start, end).trim();
        }
        return "";
    }

    static String extractPartialFromVoskResult(String json) {
        if (json == null || json.isBlank()) return "";
        int partialIdx = json.indexOf("\"partial\" : \"");
        if (partialIdx < 0) partialIdx = json.indexOf("\"partial\":\"");
        if (partialIdx >= 0) {
            int start = json.indexOf('"', partialIdx + 11) + 1;
            int end = json.indexOf('"', start);
            if (start > 0 && end > start) return json.substring(start, end).trim();
        }
        return "";
    }

    // ── State Persistence (Jackson — no escaping bugs) ─────────────

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private void loadState() {
        try {
            if (Files.exists(stateFile) && Files.size(stateFile) > 0) {
                state = JSON.readValue(stateFile.toFile(), TrainingState.class);
                LOG.info("Resumed training state: " + state.processedCount + " articles, "
                        + state.wordsLearned + " words");
            } else {
                state = new TrainingState();
            }
        } catch (Exception e) {
            LOG.warning("Failed to load training state: " + e.getMessage() + " — starting fresh");
            state = new TrainingState();
        }
    }

    private void saveState() {
        try {
            Files.createDirectories(stateFile.getParent());
            JSON.writeValue(stateFile.toFile(), state);
        } catch (IOException e) {
            LOG.warning("Failed to save training state: " + e.getMessage());
        }
    }

    // ── State Record ──────────────────────────────────────────────

    public static class TrainingState {
        @JsonProperty int processedCount = 0;
        @JsonProperty int wordsLearned = 0;
        @JsonProperty Set<String> processedTitles = new LinkedHashSet<>();
        @JsonProperty List<String> pendingSentences = new ArrayList<>();

        int remainingArticles() { return Math.max(0, 9 - processedCount); }
    }
}
