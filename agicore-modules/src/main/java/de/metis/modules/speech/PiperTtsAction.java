package de.metis.modules.speech;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Neural text-to-speech via Piper CLI (local, fast, German support).
 * <p>
 * Piper is a fast neural TTS engine optimized for Raspberry Pi.
 * German voices available: de_DE-thorsten-medium, de_DE-eva_k-x_low.
 * <p>
 * This action runs Piper as a subprocess, writes the audio to a temp WAV file,
 * and returns the file path. MaryTTS serves as the Java-native evolvable alternative.
 *
 * @see MaryTtsAction for Java-native counterpart
 */
public class PiperTtsAction implements Action {

    private static final Logger LOG = Logger.getLogger(PiperTtsAction.class.getName());
    private static final String NAME = "tts-piper";
    private static final String DEFAULT_MODEL = System.getProperty("user.home") + "/piper-voices/de_DE-thorsten-medium.onnx";
    private static final String PIPER_BIN = "piper";

    private final String text;
    private final String model;

    public PiperTtsAction(String text) {
        this(text, DEFAULT_MODEL);
    }

    public PiperTtsAction(String text, String model) {
        this.text = text;
        this.model = model;
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "write"; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        Path outputFile = null;
        try {
            outputFile = Files.createTempFile("metis-tts-", ".wav");

            ProcessBuilder pb = new ProcessBuilder(
                    PIPER_BIN,
                    "--model", model,
                    "--output_file", outputFile.toString()
            );
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);

            Process proc = pb.start();
            // Write text to stdin
            proc.getOutputStream().write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            proc.getOutputStream().close();

            boolean finished = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return ActionResult.fail(NAME, "Piper TTS timeout (30s)", start);
            }

            if (proc.exitValue() != 0) {
                return ActionResult.fail(NAME, "Piper exited with code " + proc.exitValue(), start);
            }

            byte[] audio = Files.readAllBytes(outputFile);
            long ms = Duration.between(start, Instant.now()).toMillis();
            LOG.info(() -> "Piper TTS: \"" + text.substring(0, Math.min(50, text.length()))
                    + "...\" → " + audio.length + " bytes, " + ms + "ms");
            return ActionResult.ok(NAME,
                    String.format("TTS: %d bytes (%dms) → %s", audio.length, ms, outputFile), start);
        } catch (Exception e) {
            LOG.warning("Piper TTS failed: " + e.getMessage());
            return ActionResult.fail(NAME, e.getMessage(), start);
        }
    }
}
