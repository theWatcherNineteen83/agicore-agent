package de.metis.modules.speech;

import de.metis.kernel.action.ActionResult;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Neural TTS using sherpa-onnx with Piper/Kokoro/VITS models.
 * <p>
 * Requires sherpa-onnx JARs on the classpath:
 * <ul>
 *   <li>sherpa-onnx-v1.12.10.jar</li>
 *   <li>sherpa-onnx-native-lib-linux-x64-v1.12.10.jar</li>
 * </ul>
 * <p>
 * Falls back to MaryTTS if sherpa-onnx is not available.
 * <p>
 * Design: claude_antwort_2.txt, 2026-05-28.
 */
public class SherpaOnnxTtsAction {

    private static final Logger LOG = Logger.getLogger(SherpaOnnxTtsAction.class.getName());
    private static final String NAME = "tts-sherpa-onnx";

    private static final String SHERPA_JAR = "sherpa-onnx-v1.12.10.jar";
    private static final String SHERPA_NATIVE_JAR = "sherpa-onnx-native-lib-linux-x64-v1.12.10.jar";

    private static final String PIPER_MODEL_URL =
            "https://huggingface.co/Thorsten-Voice/Piper/resolve/main/de_DE-thorsten-high.onnx";
    private static final String PIPER_CONFIG_URL =
            "https://huggingface.co/Thorsten-Voice/Piper/resolve/main/de_DE-thorsten-high.onnx.json";

    private final Path libDir;
    private final Path modelPath;
    private final Path configPath;
    private boolean available = false;
    private String status = "uninitialized";

    /**
     * @param libDir directory for sherpa-onnx JARs and models
     */
    public SherpaOnnxTtsAction(Path libDir) {
        this.libDir = libDir;
        this.modelPath = libDir.resolve("de_DE-thorsten-high.onnx");
        this.configPath = libDir.resolve("de_DE-thorsten-high.onnx.json");
        checkAvailability();
    }

    /**
     * Check if sherpa-onnx JARs and Piper model are available.
     */
    private void checkAvailability() {
        boolean jarOk = Files.exists(libDir.resolve(SHERPA_JAR))
                && Files.exists(libDir.resolve(SHERPA_NATIVE_JAR));
        boolean modelOk = Files.exists(modelPath) && Files.exists(configPath);

        if (jarOk && modelOk) {
            available = true;
            status = "ready";
            LOG.info("SherpaOnnxTTS: ready (Piper de_DE-thorsten)");
        } else if (jarOk) {
            status = "no-model";
            LOG.info("SherpaOnnxTTS: JARs found, model missing. Run downloadModel()");
        } else {
            status = "no-jars";
            LOG.info("SherpaOnnxTTS: JARs not found. Using MaryTTS fallback.");
        }
    }

    /**
     * Speak text using sherpa-onnx or MaryTTS fallback.
     *
     * @param text     German text to speak
     * @param outputWav path for output WAV file
     * @return ActionResult
     */
    public ActionResult speak(String text, Path outputWav) {
        Instant started = Instant.now();

        if (available) {
            return speakWithSherpa(text, outputWav, started);
        }

        LOG.fine("SherpaOnnxTTS not available (" + status + ") — using MaryTTS fallback");
        return speakWithMaryTts(text, started);
    }

    /**
     * Speak using MaryTTS fallback.
     */
    private ActionResult speakWithMaryTts(String text, Instant started) {
        try {
            MaryTtsAction mary = new MaryTtsAction(text);
            return mary.execute();
        } catch (Exception e) {
            return ActionResult.fail(NAME, "MaryTTS fallback failed: " + e.getMessage(), started);
        }
    }

    /**
     * Speak using sherpa-onnx via subprocess (MVP approach).
     * Full Java-native integration will use com.k2fsa.sherpa.onnx.OfflineTts
     * once the JARs are on the classpath.
     */
    private ActionResult speakWithSherpa(String text, Path outputWav, Instant started) {
        try {
            // Build classpath with sherpa-onnx JARs
            String cp = libDir.resolve(SHERPA_JAR) + ":" + libDir.resolve(SHERPA_NATIVE_JAR);

            // Run sherpa-onnx TTS via its CLI (subprocess — MVP)
            ProcessBuilder pb = new ProcessBuilder(
                    "java", "-cp", cp,
                    "com.k2fsa.sherpa.onnx.OfflineTtsDemo",
                    "--vits-model=" + modelPath,
                    "--vits-tokens=" + libDir.resolve("tokens.txt"),
                    "--vits-data-dir=" + libDir.resolve("espeak-ng-data"),
                    "--output-filename=" + outputWav,
                    text
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            int exitCode = p.waitFor();

            if (exitCode == 0 && Files.exists(outputWav) && Files.size(outputWav) > 100) {
                String preview = text.length() > 80 ? text.substring(0, 80) + "..." : text;
                return ActionResult.ok(NAME, "SherpaOnnxTTS: " + preview, started);
            }

            LOG.warning("SherpaOnnxTTS failed (exit=" + exitCode + "): " + output);
            return speakWithMaryTts(text, started);

        } catch (Exception e) {
            LOG.warning("SherpaOnnxTTS error: " + e.getMessage() + " — falling back to MaryTTS");
            return speakWithMaryTts(text, started);
        }
    }

    /**
     * Download Piper de_DE-thorsten ONNX model files.
     * Requires wget and ~80 MB disk space.
     */
    public boolean downloadModel() {
        try {
            Files.createDirectories(libDir);
            LOG.info("Downloading Piper de_DE-thorsten ONNX model...");

            downloadFile(PIPER_MODEL_URL, modelPath);
            downloadFile(PIPER_CONFIG_URL, configPath);

            if (Files.exists(modelPath) && Files.size(modelPath) > 1_000_000) {
                LOG.info("Piper model downloaded: " + modelPath + " (" +
                        Files.size(modelPath) / 1_000_000 + " MB)");
                checkAvailability();
                return true;
            }
            return false;
        } catch (Exception e) {
            LOG.severe("Model download failed: " + e.getMessage());
            return false;
        }
    }

    private void downloadFile(String url, Path dest) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("wget", "-q", "-O", dest.toString(), url);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new IOException("wget failed (exit=" + exitCode + "): " + out);
        }
    }

    public boolean isAvailable() { return available; }
    public String status() { return status; }
}
