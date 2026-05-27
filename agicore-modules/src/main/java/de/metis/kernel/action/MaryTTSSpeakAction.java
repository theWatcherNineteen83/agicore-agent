package de.metis.kernel.action;

import marytts.LocalMaryInterface;
import marytts.MaryInterface;
import marytts.exceptions.MaryConfigurationException;
import marytts.exceptions.SynthesisException;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Text-to-Speech action using MaryTTS (DFKI).
 * Pure Java TTS with native German support.
 * <p>
 * Category: write (produces audio output)
 * Requires human approval: no (read-aloud only, no external effects)
 */
public class MaryTTSSpeakAction implements Action {

    private static final Logger LOG = Logger.getLogger(MaryTTSSpeakAction.class.getName());
    public static final String NAME = "speakTTS";

    private final String text;
    private final String voice;

    private static volatile MaryInterface mary;

    public MaryTTSSpeakAction(String text) {
        this(text, "bits1-hsmm");  // Java 17+ patched German voice
    }

    public MaryTTSSpeakAction(String text, String voice) {
        this.text = text;
        this.voice = voice;
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "write"; }
    @Override public boolean requiresApproval() { return false; }

    @Override
    public ActionResult execute() {
        long start = System.currentTimeMillis();
        try {
            MaryInterface m = getMary();

            // Try to set voice, fall back to default
            try {
                m.setVoice(voice);
            } catch (Exception e) {
                LOG.warning(() -> "Voice '" + voice + "' unavailable, using default: " + e.getMessage());
            }

            // MaryTTS 5.x returns AudioInputStream directly
            AudioInputStream audio = m.generateAudio(text);

            // Read audio bytes for playback/measurement
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int read;
            while ((read = audio.read(buf)) != -1) {
                baos.write(buf, 0, read);
            }
            byte[] audioBytes = baos.toByteArray();

            // Get audio format from the stream itself
            AudioFormat fmt = audio.getFormat();

            // Play through system audio
            try {
                AudioInputStream playStream = new AudioInputStream(
                        new java.io.ByteArrayInputStream(audioBytes),
                        fmt,
                        audioBytes.length / fmt.getFrameSize()
                );
                Clip clip = AudioSystem.getClip();
                clip.open(playStream);
                clip.start();
                long durationMs = (long) (audioBytes.length /
                        (fmt.getSampleRate() * fmt.getFrameSize()) * 1000);
                Thread.sleep(durationMs + 500);
                clip.close();
            } catch (LineUnavailableException e) {
                // Headless mode: return audio bytes
                return ActionResult.ok(NAME,
                        "Audio generated (headless): " + audioBytes.length + " bytes",
                        java.time.Instant.now());
            }

            long elapsed = System.currentTimeMillis() - start;
            return ActionResult.ok(NAME,
                    "Spoke: " + (text.length() > 80 ? text.substring(0, 80) + "..." : text)
                    + " (" + elapsed + "ms)",
                    java.time.Instant.now());
        } catch (SynthesisException | IOException e) {
            return ActionResult.fail(NAME, "MaryTTS synthesis: " + e.getMessage(),
                    java.time.Instant.now());
        } catch (MaryConfigurationException e) {
            return ActionResult.fail(NAME, "MaryTTS config: " + e.getMessage(),
                    java.time.Instant.now());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.fail(NAME, "Speech interrupted", java.time.Instant.now());
        }
    }

    private static synchronized MaryInterface getMary() throws MaryConfigurationException {
        if (mary == null) {
            mary = new LocalMaryInterface();
        }
        return mary;
    }
}
