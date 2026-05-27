package de.metis.modules.speech;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import javax.sound.sampled.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Play a WAV audio file through the system audio output.
 * <p>
 * Uses Java Sound API — no external dependencies.
 * Requires a connected speaker/headphones on the default audio device.
 */
public class AudioOutputAction implements Action {

    private static final Logger LOG = Logger.getLogger(AudioOutputAction.class.getName());
    private static final String NAME = "audio-output";

    private final Path wavFile;

    public AudioOutputAction(Path wavFile) {
        this.wavFile = wavFile;
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "write"; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            if (!Files.exists(wavFile)) {
                return ActionResult.fail(NAME, "WAV file not found: " + wavFile, start);
            }

            AudioInputStream stream = AudioSystem.getAudioInputStream(wavFile.toFile());
            AudioFormat format = stream.getFormat();
            Clip clip = AudioSystem.getClip();
            clip.open(stream);

            long durationMs = clip.getMicrosecondLength() / 1000;
            LOG.info(() -> "Playing: " + wavFile.getFileName() + " (" + durationMs + "ms, "
                    + format.getChannels() + "ch, " + (int)format.getSampleRate() + "Hz)");

            clip.start();
            // Wait for playback to complete
            Thread.sleep(durationMs + 100);
            clip.close();
            stream.close();

            long ms = Duration.between(start, Instant.now()).toMillis();
            return ActionResult.ok(NAME, "Played " + durationMs + "ms audio (" + ms + "ms total)", start);
        } catch (Exception e) {
            LOG.warning("Audio output failed: " + e.getMessage());
            return ActionResult.fail(NAME, e.getMessage(), start);
        }
    }
}
