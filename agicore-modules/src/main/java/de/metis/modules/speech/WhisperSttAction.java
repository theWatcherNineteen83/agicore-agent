package de.metis.modules.speech;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * German speech-to-text via whisper.cpp CLI (local, fast, multilingual).
 * <p>
 * whisper.cpp is a C/C++ reimplementation of OpenAI's Whisper model,
 * optimized for CPU inference. Supports German with tiny/small models.
 * <p>
 * This action runs whisper.cpp as a subprocess, reads a WAV file,
 * and returns the transcribed text. Sphinx4 serves as the Java-native
 * evolvable alternative.
 *
 * @see SphinxSttAction for Java-native counterpart
 */
public class WhisperSttAction implements Action {

    private static final Logger LOG = Logger.getLogger(WhisperSttAction.class.getName());
    private static final String NAME = "stt-whisper";
    private static final String DEFAULT_MODEL = System.getProperty("user.home") + "/ggml-tiny.bin";
    private static final String WHISPER_BIN = System.getProperty("user.home") + "/bin/whisper-cpp";

    private final Path audioFile;
    private final String model;
    private final String language;

    /** Transcribe audio with German language hint. */
    public WhisperSttAction(Path audioFile) {
        this(audioFile, DEFAULT_MODEL, "de");
    }

    public WhisperSttAction(Path audioFile, String model, String language) {
        this.audioFile = audioFile;
        this.model = model;
        this.language = language;
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "write"; } // produces text output (cognitive change)

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            if (!Files.exists(audioFile)) {
                return ActionResult.fail(NAME, "Audio file not found: " + audioFile, start);
            }

            ProcessBuilder pb = new ProcessBuilder(
                    WHISPER_BIN,
                    "-m", model,
                    "-l", language,
                    "--no-timestamps",
                    "-f", audioFile.toString()
            );
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);

            Process proc = pb.start();
            boolean finished = proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return ActionResult.fail(NAME, "Whisper STT timeout (60s)", start);
            }

            String text = new String(proc.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();

            if (text.isEmpty() && proc.exitValue() != 0) {
                return ActionResult.fail(NAME, "Whisper exited with code " + proc.exitValue(), start);
            }

            long ms = Duration.between(start, Instant.now()).toMillis();
            LOG.info(() -> "Whisper STT: " + audioFile.getFileName()
                    + " → \"" + text.substring(0, Math.min(80, text.length())) + "\" (" + ms + "ms)");
            return ActionResult.ok(NAME, text, start);
        } catch (Exception e) {
            LOG.warning("Whisper STT failed: " + e.getMessage());
            return ActionResult.fail(NAME, e.getMessage(), start);
        }
    }
}
