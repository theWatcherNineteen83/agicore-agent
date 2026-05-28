package de.metis.modules.speech;

import de.metis.kernel.action.MaryTTSSpeakAction;
import de.metis.kernel.action.VocabularyLearningAction;
import de.metis.kernel.action.VoskListenAction;
import de.metis.kernel.goal.GoalManager;

import java.io.*;
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
 *   <li>Track progress via JSON state file (resumable)</li>
 * </ol>
 * <p>
 * Learning loop: Speak → Hear → Compare → Improve
 * <ul>
 *   <li><b>Speak:</b> MaryTTS/Piper generates audio from text</li>
 *   <li><b>Hear:</b> Vosk/Whisper transcribes audio back to text</li>
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
    private final int listenSeconds;    // STT listen duration

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
     */
    public WikipediaTrainingService(Path articleDir, GoalManager goalManager) {
        this.articleDir = articleDir;
        this.goalManager = goalManager;
        this.speechInterval = 2;          // speech train every 2 articles
        this.maxSentencesPerArticle = 3;  // extract 3 key sentences
        this.listenSeconds = 4;           // 4 seconds listening
        this.stateFile = Path.of("/tmp/metis-wiki-train-state.json");
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
        if (sentences.size() > 0) {
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

    // CLI paths (Piper + Whisper statt MaryTTS/Vosk)
    private static final String PIPER_BIN = "/usr/local/bin/piper";
    private static final String PIPER_MODEL = "/home/prometheus/piper-voices/de_DE-thorsten-medium.onnx";
    private static final String WHISPER_BIN = "/home/prometheus/.local/bin/whisper";
    private static final String WHISPER_MODEL = "tiny";

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
                ttsProc.getOutputStream().write(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                ttsProc.getOutputStream().close();

                boolean ttsOk = ttsProc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                if (!ttsOk || ttsProc.exitValue() != 0) {
                    LOG.fine("Piper TTS failed for sentence, skipping");
                    try { Files.deleteIfExists(wavFile); } catch (IOException ignored) {}
                    continue;
                }

                // 2. HEAR: Whisper STT from WAV file
                ProcessBuilder sttPb = new ProcessBuilder(
                        WHISPER_BIN, wavFile.toString(),
                        "--model", WHISPER_MODEL,
                        "--language", "de",
                        "--output_format", "txt",
                        "--output_dir", "/tmp"
                );
                sttPb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process sttProc = sttPb.start();
                boolean sttOk = sttProc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);

                // Whisper writes output to <output_dir>/<filename>.<ext>
                // e.g. /tmp/metis-speech-XXXXX.wav.txt
                Path transcriptFile = Path.of("/tmp", wavFile.getFileName().toString() + ".txt");
                String heard = "";
                if (sttOk && sttProc.exitValue() == 0 && Files.exists(transcriptFile)) {
                    heard = Files.readString(transcriptFile).trim();
                }

                // Cleanup temp files
                try { Files.deleteIfExists(wavFile); } catch (IOException ignored) {}
                try { Files.deleteIfExists(transcriptFile); } catch (IOException ignored) {}

                if (heard.isEmpty()) {
                    LOG.fine("Whisper produced no output");
                    continue;
                }

                // 3. COMPARE: Calculate word similarity
                double similarity = wordSimilarity(original, heard);
                LOG.info("Speech: \"" + original.substring(0, Math.min(60, original.length()))
                        + "\" → heard \"" + heard + "\" (" + String.format("%.0f%%", similarity * 100) + ")");

                // 4. IMPROVE: Learn if similarity < 80%
                if (similarity < 0.8) {
                    // Try VocabularyLearningAction first, fallback gracefully
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
                // Continue with next sentence — don't abort the whole cycle
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

        // Count matching words
        Set<String> origSet = new HashSet<>(Arrays.asList(origWords));
        Set<String> heardSet = new HashSet<>(Arrays.asList(heardWords));

        Set<String> intersection = new HashSet<>(origSet);
        intersection.retainAll(heardSet);

        return (double) intersection.size() / origSet.size();
    }

    // ── State Persistence ─────────────────────────────────────────

    private void loadState() {
        try {
            if (Files.exists(stateFile)) {
                String json = Files.readString(stateFile);
                state = TrainingState.fromJson(json);
                LOG.info("Resumed training state: " + state.processedCount + " articles, "
                        + state.wordsLearned + " words");
            } else {
                state = new TrainingState();
            }
        } catch (IOException e) {
            state = new TrainingState();
        }
    }

    private void saveState() {
        try {
            Files.createDirectories(stateFile.getParent());
            Files.writeString(stateFile, state.toJson());
        } catch (IOException e) {
            LOG.fine("Failed to save training state: " + e.getMessage());
        }
    }

    // ── State Record ──────────────────────────────────────────────

    public static class TrainingState {
        int processedCount = 0;
        int wordsLearned = 0;
        Set<String> processedTitles = new LinkedHashSet<>();
        List<String> pendingSentences = new ArrayList<>();

        int remainingArticles() { return Math.max(0, 9 - processedCount); } // target: 9

        String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"processedCount\": ").append(processedCount).append(",\n");
            sb.append("  \"wordsLearned\": ").append(wordsLearned).append(",\n");
            sb.append("  \"processedTitles\": [");
            var it = processedTitles.iterator();
            while (it.hasNext()) {
                sb.append("\"").append(escapeJson(it.next())).append("\"");
                if (it.hasNext()) sb.append(", ");
            }
            sb.append("],\n");
            sb.append("  \"pendingSentences\": [");
            var sit = pendingSentences.iterator();
            while (sit.hasNext()) {
                sb.append("\"").append(escapeJson(sit.next())).append("\"");
                if (sit.hasNext()) sb.append(", ");
            }
            sb.append("]\n");
            sb.append("}");
            return sb.toString();
        }

        static TrainingState fromJson(String json) {
            var ts = new TrainingState();
            ts.processedCount = extractInt(json, "processedCount");
            ts.wordsLearned = extractInt(json, "wordsLearned");

            // Parse processedTitles array
            int titlesStart = json.indexOf("\"processedTitles\":");
            if (titlesStart >= 0) {
                int arrStart = json.indexOf('[', titlesStart);
                int arrEnd = json.indexOf(']', arrStart);
                if (arrStart >= 0 && arrEnd > arrStart) {
                    String arr = json.substring(arrStart + 1, arrEnd);
                    for (String item : arr.split(",")) {
                        String title = item.trim().replaceAll("^\"|\"$", "");
                        if (!title.isBlank()) ts.processedTitles.add(title);
                    }
                }
            }

            // Parse pendingSentences
            int sentStart = json.indexOf("\"pendingSentences\":");
            if (sentStart >= 0) {
                int arrStart = json.indexOf('[', sentStart);
                int arrEnd = json.indexOf(']', arrStart);
                if (arrStart >= 0 && arrEnd > arrStart) {
                    String arr = json.substring(arrStart + 1, arrEnd);
                    for (String item : arr.split("\",\"|^\"|\"$")) {
                        String sent = item.trim().replaceAll("^\"|\"$", "");
                        if (!sent.isBlank()) ts.pendingSentences.add(sent);
                    }
                }
            }

            return ts;
        }

        private static int extractInt(String json, String key) {
            String search = "\"" + key + "\": ";
            int start = json.indexOf(search);
            if (start < 0) return 0;
            start += search.length();
            StringBuilder num = new StringBuilder();
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (Character.isDigit(c)) num.append(c);
                else break;
            }
            try { return Integer.parseInt(num.toString()); }
            catch (NumberFormatException e) { return 0; }
        }

        private static String escapeJson(String s) {
            return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
