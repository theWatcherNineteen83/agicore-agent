package de.metis.kernel.action;

import org.vosk.LibVosk;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Speech-to-Text action using Vosk offline recognizer.
 * Uses Vosk Java bindings with a German acoustic model.
 * <p>
 * Category: read (observing microphone input)
 * Requires human approval: no (listening, no external effects)
 * <p>
 * Model path must be set via system property {@code vosk.model.path}
 * or defaults to {@code /data/prometheus/vosk-model-de}.
 */
public class VoskListenAction implements Action {

    public static final String NAME = "listenSTT";
    private static final Logger LOG = Logger.getLogger(VoskListenAction.class.getName());
    private static final int DEFAULT_DURATION_SECONDS = 5;
    private static final float SAMPLE_RATE = 16000f;
    private final int durationSeconds;
    private final String modelPath;
    private static volatile Model model;

    public VoskListenAction() {
        this(DEFAULT_DURATION_SECONDS);
    }

    public VoskListenAction(int durationSeconds) {
        this(durationSeconds, System.getProperty("vosk.model.path",
                "/data/prometheus/vosk-model-de/vosk-model-small-de-0.15"));
    }

    public VoskListenAction(int durationSeconds, String modelPath) {
        if (durationSeconds <= 0 || durationSeconds > 60) {
            throw new IllegalArgumentException("durationSeconds must be 1-60, got " + durationSeconds);
        }
        this.durationSeconds = durationSeconds;
        this.modelPath = modelPath;
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "read"; }
    @Override public boolean requiresApproval() { return false; }

    @Override
    public ActionResult execute() {
        long startMs = System.currentTimeMillis();
        var startInstant = java.time.Instant.now();
        LibVosk.vosk_set_log_level(-1); // WARNINGS level

        try {
            Model m = getModel();
            Recognizer rec = new Recognizer(m, SAMPLE_RATE);
            rec.setWords(true);
            rec.setPartialWords(true);

            // Open microphone
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

            if (!AudioSystem.isLineSupported(info)) {
                rec.close();
                return ActionResult.fail(NAME, "Microphone not available", startInstant);
            }

            TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();

            // Record and feed to recognizer
            byte[] buffer = new byte[4096];
            long endTime = System.currentTimeMillis() + durationSeconds * 1000L;
            ByteArrayOutputStream fullAudio = new ByteArrayOutputStream();
            String finalResult = "";

            while (System.currentTimeMillis() < endTime) {
                int bytesRead = line.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {
                    fullAudio.write(buffer, 0, bytesRead);
                    if (rec.acceptWaveForm(buffer, bytesRead)) {
                        finalResult = rec.getResult();
                    }
                }
            }

            line.stop();
            line.close();

            // Get final result
            String partialResult = rec.getPartialResult();
            if (!finalResult.isEmpty()) {
                finalResult = extractText(finalResult);
            } else if (!partialResult.isEmpty()) {
                finalResult = extractText(partialResult);
            }

            rec.close();

            long elapsed = System.currentTimeMillis() - startMs;
            if (finalResult.isEmpty()) {
                return ActionResult.ok(NAME,
                        "(silence)", startInstant);
            }

            return ActionResult.ok(NAME, finalResult, startInstant);

        } catch (LineUnavailableException e) {
            return ActionResult.fail(NAME, "Audio line unavailable: " + e.getMessage(), startInstant);
        } catch (Exception e) {
            return ActionResult.fail(NAME, "Vosk STT failed: " + e.getMessage(), startInstant);
        }
    }

    private String extractText(String jsonResult) {
        // Vosk returns: {"text": "erkannte Worte"}
        try {
            // Simple extraction without full JSON parsing
            int start = jsonResult.indexOf("\"text\"");
            if (start >= 0) {
                start = jsonResult.indexOf("\"", jsonResult.indexOf(":", start)) + 1;
                int end = jsonResult.indexOf("\"", start);
                if (end > start) {
                    return jsonResult.substring(start, end).trim();
                }
            }
        } catch (Exception e) {
            LOG.fine("Failed to parse Vosk result: " + e.getMessage());
        }
        return jsonResult.replaceAll("[{}\"]", "").trim();
    }

    private synchronized Model getModel() throws IOException {
        if (model == null) {
            LOG.info("Loading Vosk model from: " + modelPath);
            model = new Model(modelPath);
        }
        return model;
    }

    /**
     * Release the model resources. Call on shutdown.
     */
    public static synchronized void releaseModel() {
        if (model != null) {
            model.close();
            model = null;
        }
    }
}
