package de.metis.modules.speech;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Record audio from the default microphone to a WAV file.
 * <p>
 * Uses Java Sound API — no external dependencies.
 * Records mono 16kHz 16-bit PCM, optimized for speech recognition (whisper.cpp).
 *
 * @see WhisperSttAction for transcription
 */
public class AudioInputAction implements Action {

    private static final Logger LOG = Logger.getLogger(AudioInputAction.class.getName());
    private static final String NAME = "audio-input";

    private final Path outputFile;
    private final int durationSeconds;

    /** Record for specified duration, save to temp file. */
    public AudioInputAction(int durationSeconds) {
        this(durationSeconds, null);
    }

    public AudioInputAction(int durationSeconds, Path outputFile) {
        this.durationSeconds = Math.clamp(durationSeconds, 1, 60);
        this.outputFile = outputFile;
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "read"; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        final Path wavFile;
        try {
            wavFile = (outputFile != null) ? outputFile : Files.createTempFile("metis-mic-", ".wav");

            // 16kHz mono 16-bit PCM — optimal for whisper.cpp
            AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

            if (!AudioSystem.isLineSupported(info)) {
                return ActionResult.fail(NAME, "No microphone found (unsupported format)", start);
            }

            TargetDataLine mic = (TargetDataLine) AudioSystem.getLine(info);
            mic.open(format);
            mic.start();

            final int rate = (int) format.getSampleRate();
            LOG.info(() -> "Recording " + durationSeconds + "s @ " + rate
                    + "Hz → " + wavFile.getFileName());

            // Record directly (synchronous — acceptable for 5-10s voice input)
            byte[] buffer = new byte[mic.getBufferSize() / 5];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long endTime = System.currentTimeMillis() + durationSeconds * 1000L;

            while (System.currentTimeMillis() < endTime) {
                int count = mic.read(buffer, 0, buffer.length);
                if (count > 0) {
                    out.write(buffer, 0, count);
                }
            }
            mic.stop();
            mic.close();

            // Write WAV with proper header
            byte[] audioData = out.toByteArray();
            AudioInputStream ais = new AudioInputStream(
                    new java.io.ByteArrayInputStream(audioData),
                    format,
                    audioData.length / format.getFrameSize());
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavFile.toFile());
            ais.close();

            long fileSize = Files.size(wavFile);
            long ms = Duration.between(start, Instant.now()).toMillis();
            final long finalSize = fileSize;
            final long finalMs = ms;
            LOG.info(() -> "Recorded: " + finalSize + " bytes, " + finalMs + "ms → " + wavFile);
            return ActionResult.ok(NAME,
                    String.format("%d bytes (%ds) → %s", fileSize, durationSeconds, wavFile), start);
        } catch (LineUnavailableException e) {
            LOG.warning("Microphone unavailable: " + e.getMessage());
            return ActionResult.fail(NAME, "Microphone unavailable: " + e.getMessage(), start);
        } catch (Exception e) {
            LOG.warning("Audio input failed: " + e.getMessage());
            return ActionResult.fail(NAME, e.getMessage(), start);
        }
    }
}
