package de.metis.modules.action;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

/**
 * Phase 13a — Voice Feature Extraction Action (Lusseyran Pipeline).
 * <p>
 * Calls the Python voice_feature_extractor.py script to extract
 * 25+ paralinguistic features from a WAV audio file.
 * <p>
 * The Python script uses numpy + scipy (no librosa/parselmouth needed)
 * and outputs JSON with Lusseyran-categorized features:
 * pitch, energy, rhythm, timbre, formant, voice_quality.
 *
 * <p>Approval level: READ — non-destructive, analysis-only.
 */
public class VoiceFeatureAction implements Action {

    private static final Logger LOG = Logger.getLogger(VoiceFeatureAction.class.getName());
    public static final String NAME = "voice_feature";

    private static final Path SCRIPT =
            Path.of("/home/prometheus/metis/voice_feature_extractor.py");
    private static final Path PYTHON = Path.of("/usr/bin/python3");

    private final Path audioFile;

    public VoiceFeatureAction(Path audioFile) {
        // Lazy validation: file checked in execute(), not constructor.
        // Allows registration without a concrete file at startup.
        this.audioFile = audioFile;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String category() { return "analyze"; }

    @Override
    public ApprovalLevel approvalLevel() { return ApprovalLevel.NOTIFY; }

    @Override
    public ActionResult execute() {
        if (audioFile == null || !Files.exists(audioFile)) {
            return ActionResult.fail(NAME,
                    "Audio file not found: " + audioFile, Instant.now());
        }
        Instant start = Instant.now();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    PYTHON.toString(), SCRIPT.toString(), audioFile.toString());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return ActionResult.fail(NAME, "VoiceFeature extraction timed out", start);
            }
            String output;
            try (var in = proc.getInputStream()) {
                output = new String(in.readAllBytes()).strip();
            }
            int exit = proc.exitValue();
            if (exit != 0) {
                return ActionResult.fail(NAME, "VoiceFeature exit " + exit + ": " + output, start);
            }
            LOG.fine(() -> "VoiceFeature extracted: " + audioFile.getFileName()
                    + " (" + output.length() + " bytes JSON)");
            return ActionResult.ok(NAME, output, start);
        } catch (IOException e) {
            return ActionResult.fail(NAME, "IO error: " + e.getMessage(), start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.fail(NAME, "Interrupted", start);
        }
    }

    @Override
    public String toString() {
        return "VoiceFeatureAction[" + audioFile.getFileName() + "]";
    }
}
